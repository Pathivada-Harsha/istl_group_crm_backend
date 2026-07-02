package com.istlgroup.istl_group_crm_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants;
import com.istlgroup.istl_group_crm_backend.repo.*;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProjectExpenseService {

    private final ProjectExpenseRepository        expenseRepo;
    private final ProjectAdvanceRepository        advanceRepo;
    private final ProjectExpenseHistoryRepository historyRepo;
    private final ProjectRepository               projectRepo;
    private final UsersRepo                       usersRepo;
    private final ExpenseBillRepository           billRepo;
    private final ObjectMapper                    objectMapper;
    private final ProjectAccessService            projectAccessService;
    private final com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo roleHierarchyRepo;
    private final MailService                     mailService;
    private final NotificationService             notificationService;

    private static final long MAX_BILL_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final List<String> ALLOWED_BILL_TYPES = Arrays.asList(
        "application/pdf", "image/jpeg", "image/jpg", "image/png", "image/webp");

    // =========================================================================
    // EXPENSE CRUD
    // =========================================================================

    public List<ProjectExpenseResponse> createExpenses(
            ProjectExpenseRequest request, Long userId, String userName) {

        // Expense code is derived from the DB-generated id (see below).
        // Use a temporary placeholder that satisfies the NOT NULL + UNIQUE
        // constraint until the real id is available after the first flush.
        final String TEMP_CODE = "__PENDING_" + System.nanoTime() + "__";

        // Calculate total from all items
        BigDecimal total = request.getExpenseItems() == null ? BigDecimal.ZERO :
            request.getExpenseItems().stream()
                .map(i -> i.getAmount() != null ? i.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Resolve the reporting manager (Stage 1 approver) from the creator ──
        Long   managerId   = null;
        String managerName = null;
        UsersEntity creator = (userId != null) ? usersRepo.findById(userId).orElse(null) : null;
        if (creator != null && creator.getManagerId() != null) {
            managerId   = creator.getManagerId();
            managerName = usersRepo.findById(managerId).map(UsersEntity::getName).orElse(null);
        }

        // Create ONE parent expense record for the entire submission.
        // Status is ALWAYS server-controlled — the new workflow starts at Pending.
        ProjectExpense.ProjectExpenseBuilder b = ProjectExpense.builder()
            .expenseCode(TEMP_CODE)
            .projectId(request.getProjectId())
            .groupName(request.getGroupName())
            .subGroupName(request.getSubGroupName())
            .tripDate(request.getTripDate())
            .tripReason(request.getTripReason())
            .totalAmount(total)
            .paidByUserId(request.getPaidByUserId())
            .paidByName(request.getPaidByName())
            .status("Pending")
            .reportingManagerId(managerId)
            .reportingManagerName(managerName)
            .paymentStatus("UNPAID")
            .createdBy(userId)
            .isDeleted(false);

        if (managerId != null) {
            // Normal flow — wait for the reporting manager first
            b.approvalStage("MANAGER")
             .stage1Status("PENDING")
             .stage2Status("WAITING")
             .stage3Status("WAITING");
        } else {
            // No reporting manager configured — skip Stage 1, go straight to CFO
            b.approvalStage("CFO")
             .stage1Status("APPROVED")
             .stage1ByName("— (no reporting manager)")
             .stage1At(LocalDateTime.now())
             .stage1Remarks("Auto-skipped: creator has no reporting manager")
             .stage2Status("PENDING")
             .stage3Status("WAITING");
        }

        // First save — DB assigns the auto-increment id.
        // flush() forces the INSERT immediately so the id is available
        // within the same transaction before we derive the expense code.
        ProjectExpense saved = expenseRepo.saveAndFlush(b.build());

        // Derive the final expense code from the generated id — only if no real code exists yet.
        // This guards against overwriting codes on records created before this logic.
        String existingCode = saved.getExpenseCode();
        String code;
        if (existingCode == null || existingCode.isBlank() || existingCode.startsWith("__PENDING_")) {
            code = buildExpenseCode(saved.getId());
            saved.setExpenseCode(code);
            expenseRepo.saveAndFlush(saved);
        } else {
            code = existingCode;
        }

        // Save child items
        if (request.getExpenseItems() != null) {
            List<ProjectExpenseItem> items = request.getExpenseItems().stream()
                .map(i -> ProjectExpenseItem.builder()
                    .expense(saved)
                    .category(i.getCategory())
                    .amount(i.getAmount())
                    .paymentMode(i.getPaymentMode())
                    .description(i.getDescription())
                    .build())
                .collect(Collectors.toList());
            // Add into the existing managed collection — never replace it.
            // Replacing would sever the reference Hibernate tracks for orphan
            // removal (CascadeType.ALL + orphanRemoval=true), causing:
            //   "A collection with orphan deletion was no longer referenced"
            saved.getExpenseItems().addAll(items);
            expenseRepo.save(saved);
        }

        // Pass `code` as the snapshot instead of the `saved` entity.
        // The entity has a bidirectional ProjectExpense<->ProjectExpenseItem
        // cycle with no @JsonIgnore, so serializing `saved` causes Jackson to
        // recurse infinitely: expense->items->expense->items->... (depth 1001).
        logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, saved.getId(),
            saved.getProjectId(), "CREATED", userId, userName,
            null, saved.getStatus(), null, saved.getTotalAmount(),
            "Expense created: " + code + " — submitted for approval", code);

        updateProjectExpenseStats(request.getProjectId());

        // Notify whoever the expense now waits on: the reporting manager, or the
        // CFO directly if the submitter had no manager (stage was auto-advanced).
        notifyPendingApprover(saved);

        return List.of(toExpenseResponse(saved));
    }

    @Transactional(readOnly = true)
    public Page<ProjectExpenseResponse> getExpenses(ExpenseFilterRequest filter) {
        Sort sort = buildSort(filter.getSortBy(), filter.getSortDir());
        Pageable pageable = PageRequest.of(filter.getPage(), filter.getSize(), sort);

        List<String> accessibleProjectIds = filter.getAccessibleProjectIds();
        Long         viewerUserId         = filter.getViewerUserId();
        List<Long>   teamMemberIds        = filter.getTeamMemberIds();

        Page<ProjectExpense> page;

        if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
            // Non-admin with project grants — scope to those projects
            page = expenseRepo.findWithFiltersAndVisibilityByProjects(
                accessibleProjectIds,
                blankToNull(filter.getGroupName()),
                blankToNull(filter.getSubGroupName()),
                nullIfAll(filter.getCategory()),
                nullIfAll(filter.getStatus()),
                nullIfAll(filter.getPaymentMode()),
                filter.getDateFrom(),
                filter.getDateTo(),
                blankToNull(filter.getSearch()),
                pageable
            );
        } else {
            // Admin (viewerUserId=null) or fallback own-records (viewerUserId=userId)
            List<Long> effectiveTeam = (teamMemberIds != null && !teamMemberIds.isEmpty())
                ? teamMemberIds : List.of(-1L);

            page = expenseRepo.findWithFiltersAndVisibility(
                blankToNull(filter.getGroupName()),
                blankToNull(filter.getSubGroupName()),
                nullIfAll(filter.getProjectId()),
                nullIfAll(filter.getCategory()),
                nullIfAll(filter.getStatus()),
                nullIfAll(filter.getPaymentMode()),
                filter.getDateFrom(),
                filter.getDateTo(),
                blankToNull(filter.getSearch()),
                viewerUserId,
                effectiveTeam,
                pageable
            );
        }

        return page.map(this::toExpenseResponse);
    }

    public void applyVisibilityToFilter(ExpenseFilterRequest filter, Long userId, String userRole) {
        // Bypass roles: SUPERADMIN, ADMIN, ACCOUNTS_*, or level_order <= 2 in DB
        if (projectAccessService.isBypassRole(userRole)) {
            filter.setViewerUserId(null);
            filter.setTeamMemberIds(null);
            filter.setAccessibleProjectIds(null);
            return;
        }

        // Non-bypass: scope to projects the user has been explicitly granted access to
        List<String> projectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
        if (projectIds != null && !projectIds.isEmpty()) {
            filter.setAccessibleProjectIds(projectIds);
            filter.setViewerUserId(null);
            filter.setTeamMemberIds(null);
        } else {
            // No project grants at all — fall back to own records (createdBy = userId)
            filter.setAccessibleProjectIds(null);
            filter.setViewerUserId(userId);
            filter.setTeamMemberIds(List.of(-1L));
        }
    }

    /**
     * isAdmin — mirrors InvoiceService.isAdmin() exactly.
     * Full access: SUPERADMIN, ADMIN, ACCOUNTS_*, or level_order <= 2 in role_hierarchy.
     */
    private boolean isAdmin(String userRole) {
        return projectAccessService.isBypassRole(userRole);
    }

    @Transactional(readOnly = true)
    public ProjectExpenseResponse getExpenseById(Long id) {
        return toExpenseResponse(findExpenseOrThrow(id));
    }

    public ProjectExpenseResponse updateExpense(
            Long id, ProjectExpenseRequest request,
            Long userId, String userName) {

        ProjectExpense expense = findExpenseOrThrow(id);
        BigDecimal oldAmount = expense.getTotalAmount();
        String oldStatus     = expense.getStatus();
        String oldProjectId  = expense.getProjectId();

        if (request.getProjectId()    != null) expense.setProjectId(request.getProjectId());
        if (request.getGroupName()    != null) expense.setGroupName(request.getGroupName());
        if (request.getSubGroupName() != null) expense.setSubGroupName(request.getSubGroupName());
        if (request.getTripDate()     != null) expense.setTripDate(request.getTripDate());
        if (request.getTripReason()   != null) expense.setTripReason(request.getTripReason());
        if (request.getPaidByUserId() != null) expense.setPaidByUserId(request.getPaidByUserId());
        if (request.getPaidByName()   != null) expense.setPaidByName(request.getPaidByName());
        // NOTE: status is no longer client-settable here — it is driven by the approval workflow.

        if (request.getExpenseItems() != null && !request.getExpenseItems().isEmpty()) {
            if (expense.getExpenseItems() != null) {
                expense.getExpenseItems().clear();
            }
            for (ProjectExpenseRequest.ExpenseItemRequest itemReq : request.getExpenseItems()) {
                ProjectExpenseItem item = new ProjectExpenseItem();
                item.setExpense(expense);
                item.setCategory(itemReq.getCategory());
                item.setAmount(itemReq.getAmount());
                item.setPaymentMode(itemReq.getPaymentMode());
                item.setDescription(itemReq.getDescription());
                expense.getExpenseItems().add(item);
            }
            BigDecimal newTotal = expense.getExpenseItems().stream()
                .map(i -> i.getAmount() != null ? i.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            expense.setTotalAmount(newTotal);
        }

        ProjectExpense saved = expenseRepo.save(expense);
        logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, saved.getId(),
            saved.getProjectId(), "UPDATED", userId, userName,
            oldStatus, saved.getStatus(), oldAmount, saved.getTotalAmount(),
            "Expense updated" + (!saved.getProjectId().equals(oldProjectId)
                ? " (project changed from " + oldProjectId + " to " + saved.getProjectId() + ")" : ""),
            saved);

        updateProjectExpenseStats(saved.getProjectId());
        if (!saved.getProjectId().equals(oldProjectId)) {
            updateProjectExpenseStats(oldProjectId);
        }
        return toExpenseResponse(saved);
    }

    // =========================================================================
    // APPROVAL WORKFLOW
    // =========================================================================

    /**
     * Acts on the CURRENT approval stage of an expense.
     * @param action  "approve" or "reject"
     * Permission is checked against the acting user's role / designation and,
     * for the manager stage, against the creator's reporting manager.
     */
    public ProjectExpenseResponse actOnApproval(
            Long id, String action, String remarks, Long actorId, String actorName) {

        ProjectExpense e = findExpenseOrThrow(id);
        ensureWorkflowInitialized(e);

        String stage = e.getApprovalStage();
        if ("COMPLETED".equals(stage)) throw badRequest("This expense is already fully approved.");
        if ("REJECTED".equals(stage))  throw badRequest("This expense has already been rejected.");

        UsersEntity actor = (actorId != null) ? usersRepo.findById(actorId).orElse(null) : null;
        String roleNorm   = norm(actor != null ? actor.getRole() : null);
        String desigNorm  = norm(actor != null ? actor.getDesignation() : null);
        boolean adminOverride = roleNorm.equals("SUPERADMIN") || roleNorm.equals("ADMIN");
        boolean reject = "reject".equalsIgnoreCase(action);

        boolean allowed;
        switch (stage) {
            case "MANAGER":
                allowed = adminOverride
                    || (e.getReportingManagerId() != null && e.getReportingManagerId().equals(actorId));
                break;
            case "CFO":
                allowed = adminOverride
                    || roleNorm.equals("ACCOUNTSCFO")
                    || desigNorm.contains("CHIEFFINANCIALOFFICER")
                    || desigNorm.equals("CFO");
                break;
            case "FINAL":
                allowed = adminOverride
                    || roleNorm.equals("ACCOUNTSMANAGER")
                    || roleNorm.equals("SUPERADMIN")
                    || desigNorm.contains("ACCOUNTSMANAGER");
                break;
            default:
                allowed = false;
        }
        if (!allowed) {
            throw forbidden("You are not authorized to act on the " + stageLabel(stage)
                + " approval stage for this expense.");
        }

        LocalDateTime now = LocalDateTime.now();
        String oldStatus = e.getStatus();

        if (reject) {
            setStage(e, stage, "REJECTED", actorId, actorName, now, remarks);
            e.setApprovalStage("REJECTED");
            e.setStatus("Rejected");
        } else {
            setStage(e, stage, "APPROVED", actorId, actorName, now, remarks);
            if ("MANAGER".equals(stage)) {
                e.setApprovalStage("CFO");
                e.setStage2Status("PENDING");
            } else if ("CFO".equals(stage)) {
                e.setApprovalStage("FINAL");
                e.setStage3Status("PENDING");
            } else if ("FINAL".equals(stage)) {
                e.setApprovalStage("COMPLETED");
                e.setStatus("Approved");
            }
        }

        ProjectExpense saved = expenseRepo.save(e);
        logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, saved.getId(),
            saved.getProjectId(),
            (reject ? "REJECTED_" : "APPROVED_") + stage, actorId, actorName,
            oldStatus, saved.getStatus(), null, null,
            (reject ? "Rejected at " : "Approved at ") + stageLabel(stage) + " stage"
                + (remarks != null && !remarks.isBlank() ? " — " + remarks : ""),
            saved);

        updateProjectExpenseStats(saved.getProjectId());

        // Email routing after the transition:
        //  • rejected            → tell the original submitter it was rejected
        //  • now COMPLETED       → tell the submitter it was fully approved
        //  • advanced a stage    → tell the next pending approver (CFO / FINAL)
        if (reject) {
            notifySubmitterFinal(saved, true);
        } else if ("COMPLETED".equals(saved.getApprovalStage())) {
            notifySubmitterFinal(saved, false);
        } else {
            notifyPendingApprover(saved);
        }

        return toExpenseResponse(saved);
    }
    public ProjectExpenseResponse markPaid(Long id, Long actorId, String actorName) {
        ProjectExpense e = findExpenseOrThrow(id);

        if (!"COMPLETED".equals(e.getApprovalStage()) || !"Approved".equalsIgnoreCase(e.getStatus())) {
            throw badRequest("Expense must be fully approved before it can be marked paid.");
        }
        if ("PAID".equalsIgnoreCase(e.getPaymentStatus())) {
            throw badRequest("This expense is already marked as paid.");
        }

        UsersEntity actor = (actorId != null) ? usersRepo.findById(actorId).orElse(null) : null;
        String roleNorm  = norm(actor != null ? actor.getRole() : null);
        String desigNorm = norm(actor != null ? actor.getDesignation() : null);
        boolean allowed = roleNorm.equals("SUPERADMIN") || roleNorm.equals("ADMIN")
            || roleNorm.equals("ACCOUNTSEXECUTIVE")
            || desigNorm.contains("ACCOUNTSEXECUTIVE");
        if (!allowed) {
            throw forbidden("Only an Accounts Executive can mark an expense as paid.");
        }

        e.setPaymentStatus("PAID");
        e.setPaidByExecId(actorId);
        e.setPaidByExecName(actorName);
        e.setPaidAt(LocalDateTime.now());
        ProjectExpense saved = expenseRepo.save(e);

        logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, saved.getId(),
            saved.getProjectId(), "PAID", actorId, actorName,
            "Approved", "Approved", null, saved.getTotalAmount(),
            "Marked as paid by " + actorName, saved);

        updateProjectExpenseStats(saved.getProjectId());
        return toExpenseResponse(saved);
    }

    /**
     * Direct status override (legacy / admin). Kept for backward compatibility.
     * Keeps the stage columns coherent with the chosen status.
     */
    public ProjectExpenseResponse updateExpenseStatus(
            Long id, String newStatus, Long userId, String userName) {

        ProjectExpense e = findExpenseOrThrow(id);
        String oldStatus = e.getStatus();
        LocalDateTime now = LocalDateTime.now();

        if ("Approved".equalsIgnoreCase(newStatus)) {
            e.setStatus("Approved");
            e.setApprovalStage("COMPLETED");
            if (!"APPROVED".equals(e.getStage1Status())) setStage(e, "MANAGER", "APPROVED", userId, userName, now, "Admin override");
            if (!"APPROVED".equals(e.getStage2Status())) setStage(e, "CFO",     "APPROVED", userId, userName, now, "Admin override");
            setStage(e, "FINAL", "APPROVED", userId, userName, now, "Admin override");
        } else if ("Rejected".equalsIgnoreCase(newStatus)) {
            String cur = (e.getApprovalStage() == null) ? "MANAGER" : e.getApprovalStage();
            String rejectStage = ("MANAGER".equals(cur) || "CFO".equals(cur) || "FINAL".equals(cur)) ? cur : "MANAGER";
            e.setStatus("Rejected");
            e.setApprovalStage("REJECTED");
            setStage(e, rejectStage, "REJECTED", userId, userName, now, "Admin override");
        } else {
            e.setStatus(newStatus);
        }

        ProjectExpense saved = expenseRepo.save(e);
        logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, saved.getId(),
            saved.getProjectId(), newStatus.toUpperCase(), userId, userName,
            oldStatus, newStatus, null, null,
            "Status changed to " + newStatus, saved);

        updateProjectExpenseStats(saved.getProjectId());
        return toExpenseResponse(saved);
    }

    /** Initialise workflow columns for legacy rows that pre-date the feature. */
    private void ensureWorkflowInitialized(ProjectExpense e) {
        if (e.getApprovalStage() != null) return;

        if ("Approved".equalsIgnoreCase(e.getStatus())) {
            e.setApprovalStage("COMPLETED");
            e.setStage1Status("APPROVED");
            e.setStage2Status("APPROVED");
            e.setStage3Status("APPROVED");
        } else if ("Rejected".equalsIgnoreCase(e.getStatus())) {
            e.setApprovalStage("REJECTED");
            e.setStage1Status("REJECTED");
        } else {
            // Pending — resolve the reporting manager from the creator
            Long managerId = null; String managerName = null;
            if (e.getCreatedBy() != null) {
                UsersEntity creator = usersRepo.findById(e.getCreatedBy()).orElse(null);
                if (creator != null && creator.getManagerId() != null) {
                    managerId = creator.getManagerId();
                    managerName = usersRepo.findById(managerId).map(UsersEntity::getName).orElse(null);
                }
            }
            e.setReportingManagerId(managerId);
            e.setReportingManagerName(managerName);
            if (managerId != null) {
                e.setApprovalStage("MANAGER");
                e.setStage1Status("PENDING");
                e.setStage2Status("WAITING");
                e.setStage3Status("WAITING");
            } else {
                e.setApprovalStage("CFO");
                e.setStage1Status("APPROVED");
                e.setStage1ByName("— (no reporting manager)");
                e.setStage2Status("PENDING");
                e.setStage3Status("WAITING");
            }
        }
        if (e.getPaymentStatus() == null) e.setPaymentStatus("UNPAID");
    }

    private void setStage(ProjectExpense e, String stage, String status,
            Long byId, String byName, LocalDateTime at, String remarks) {
        switch (stage) {
            case "MANAGER":
                e.setStage1Status(status); e.setStage1ById(byId);
                e.setStage1ByName(byName); e.setStage1At(at); e.setStage1Remarks(remarks); break;
            case "CFO":
                e.setStage2Status(status); e.setStage2ById(byId);
                e.setStage2ByName(byName); e.setStage2At(at); e.setStage2Remarks(remarks); break;
            case "FINAL":
                e.setStage3Status(status); e.setStage3ById(byId);
                e.setStage3ByName(byName); e.setStage3At(at); e.setStage3Remarks(remarks); break;
            default: break;
        }
    }

    private String stageLabel(String stage) {
        if (stage == null) return "approval";
        switch (stage) {
            case "MANAGER": return "Reporting Manager";
            case "CFO":     return "Chief Financial Officer";
            case "FINAL":   return "Final Authorization";
            default:        return stage;
        }
    }

    public void deleteExpense(Long id, Long userId, String userName) {
        ProjectExpense expense = findExpenseOrThrow(id);
        expense.setIsDeleted(true);
        expenseRepo.save(expense);

        logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, id,
            expense.getProjectId(), "DELETED", userId, userName,
            expense.getStatus(), "DELETED", expense.getTotalAmount(), null,
            "Expense soft-deleted", expense);

        updateProjectExpenseStats(expense.getProjectId());
    }

    // =========================================================================
    // BILL / RECEIPT — stored as a BLOB in the database
    // =========================================================================

    /**
     * Stores the uploaded bill bytes in the expense_bills table (one per expense)
     * and points receipt_url at the streaming endpoint. Returns that URL.
     * Method name kept (`uploadReceipt`) for controller compatibility.
     */
    public String uploadReceipt(Long id, MultipartFile file, Long userId, String userName) {
        ProjectExpense expense = findExpenseOrThrow(id);

        if (file == null || file.isEmpty()) {
            throw badRequest("No bill file provided.");
        }
        if (file.getSize() > MAX_BILL_BYTES) {
            throw badRequest("Bill file exceeds the 10 MB limit.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_BILL_TYPES.contains(contentType.toLowerCase())) {
            // Allow through if browser sent a generic type; otherwise block obvious mismatches
            log.warn("Bill upload with unusual content-type: {}", contentType);
        }

        try {
            ExpenseBill bill = billRepo.findByExpenseId(id).orElse(new ExpenseBill());
            bill.setExpenseId(id);
            bill.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : ("bill_" + id));
            bill.setMimeType(contentType != null ? contentType : "application/octet-stream");
            bill.setFileSize(file.getSize());
            bill.setFileData(file.getBytes());
            billRepo.save(bill);

            String url = "/project-expenses/" + id + "/bill";
            expense.setReceiptUrl(url);
            expenseRepo.save(expense);

            logHistory(ProjectExpenseHistory.ReferenceType.EXPENSE, id,
                expense.getProjectId(), "RECEIPT_UPLOADED", userId, userName,
                null, null, null, null,
                "Bill uploaded: " + bill.getFileName(), expense);
            return url;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store bill: " + e.getMessage());
        }
    }

    /** Streams the stored bill bytes to the client with the correct Content-Type. */
    @Transactional(readOnly = true)
    public void streamBill(Long id, HttpServletResponse response) {
        ExpenseBill bill = billRepo.findByExpenseId(id)
            .orElseThrow(() -> new EntityNotFoundException("No bill found for expense: " + id));
        try {
            response.setContentType(bill.getMimeType());
            response.setContentLength(bill.getFileData().length);
            response.setHeader("Content-Disposition",
                "inline; filename=\"" + bill.getFileName() + "\"");
            response.setHeader("Cache-Control", "private, max-age=3600");
            response.getOutputStream().write(bill.getFileData());
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new RuntimeException("Failed to stream bill: " + e.getMessage());
        }
    }

    // =========================================================================
    // ADVANCE CRUD  (unchanged)
    // =========================================================================

    public ProjectAdvanceResponse createAdvance(
            ProjectAdvanceRequest request, Long userId, String userName) {

        String code = generateAdvanceCode();
        BigDecimal total = request.getAdvancePayments().stream()
            .map(ProjectAdvanceRequest.AdvancePaymentRequest::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        ProjectAdvance advance = ProjectAdvance.builder()
            .advanceCode(code)
            .projectId(request.getProjectId())
            .groupName(request.getGroupName())
            .subGroupName(request.getSubGroupName())
            .advanceDate(request.getAdvanceDate())
            .expectedTripDate(request.getExpectedTripDate())
            .tripPurpose(request.getTripPurpose())
            .requestedByUserId(request.getRequestedByUserId())
            .requestedByName(request.getRequestedByName())
            .approvedByUserId(request.getApprovedByUserId())
            .approvedByName(request.getApprovedByName())
            .totalAdvanceAmount(total)
            .status(request.getStatus() != null ? request.getStatus() : "Pending")
            .relatedExpenseId(request.getRelatedExpenseId())
            .createdBy(userId)
            .build();

        ProjectAdvance savedAdvance = advanceRepo.save(advance);

        List<ProjectAdvancePayment> payments = request.getAdvancePayments().stream()
            .map(p -> ProjectAdvancePayment.builder()
                .advance(savedAdvance)
                .advanceNumber(p.getAdvanceNumber())
                .amount(p.getAmount())
                .paymentMode(p.getPaymentMode())
                .paymentDate(p.getPaymentDate())
                .notes(p.getNotes())
                .build())
            .collect(Collectors.toList());

        savedAdvance.setPayments(payments);
        ProjectAdvance finalAdvance = advanceRepo.save(savedAdvance);

        logHistory(ProjectExpenseHistory.ReferenceType.ADVANCE, finalAdvance.getId(),
            finalAdvance.getProjectId(), "CREATED", userId, userName,
            null, finalAdvance.getStatus(), null, total,
            "Advance recorded: " + code, finalAdvance);

        updateProjectExpenseStats(request.getProjectId());
        return toAdvanceResponse(finalAdvance);
    }

    @Transactional(readOnly = true)
    public Page<ProjectAdvanceResponse> getAdvances(String projectId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return advanceRepo.findByProjectIdOrderByCreatedAtDesc(projectId, pageable)
            .map(this::toAdvanceResponse);
    }

    @Transactional(readOnly = true)
    public ProjectAdvanceResponse getAdvanceById(Long id) {
        ProjectAdvance advance = advanceRepo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Advance not found: " + id));
        return toAdvanceResponse(advance);
    }

    public ProjectAdvanceResponse updateAdvanceStatus(
            Long id, String newStatus, Long userId, String userName) {

        ProjectAdvance advance = advanceRepo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Advance not found: " + id));
        String oldStatus = advance.getStatus();
        advance.setStatus(newStatus);
        advanceRepo.save(advance);

        logHistory(ProjectExpenseHistory.ReferenceType.ADVANCE, id,
            advance.getProjectId(), newStatus.toUpperCase(), userId, userName,
            oldStatus, newStatus, null, null, "Advance status changed", advance);

        updateProjectExpenseStats(advance.getProjectId());
        return toAdvanceResponse(advance);
    }

    // =========================================================================
    // STATS  (unchanged)
    // =========================================================================

    @Transactional(readOnly = true)
    public ProjectExpenseStatsResponse getExpenseStats(String projectId) {
        BigDecimal totalExpenses   = safe(expenseRepo.sumApprovedByProject(projectId));
        BigDecimal pendingExpenses = safe(expenseRepo.sumPendingByProject(projectId));
        BigDecimal travelSite      = safe(expenseRepo.sumTravelAndSiteVisit(projectId));
        BigDecimal commission      = safe(expenseRepo.sumCommission(projectId));
        Long       pendingCount    = expenseRepo.countPending(projectId);
        BigDecimal approvedMonth   = safe(expenseRepo.sumApprovedThisMonth(projectId));
        BigDecimal totalAdv        = safe(advanceRepo.sumTotalByProject(projectId));
        BigDecimal unsettledAdv    = safe(advanceRepo.sumUnsettledByProject(projectId));

        List<ProjectExpenseStatsResponse.UserExpenseSummary> userBreakdown =
            expenseRepo.getUserBreakdownByProject(projectId).stream()
                .map(row -> ProjectExpenseStatsResponse.UserExpenseSummary.builder()
                    .userId(row[0] != null ? ((Number) row[0]).longValue() : null)
                    .userName(row[1] != null ? row[1].toString() : "Unknown")
                    .totalAmount(row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO)
                    .expenseCount(row[3] != null ? ((Number) row[3]).longValue() : 0L)
                    .approvedAmount(row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO)
                    .pendingAmount(row[5] != null ? (BigDecimal) row[5] : BigDecimal.ZERO)
                    .build())
                .collect(Collectors.toList());

        List<ProjectExpenseStatsResponse.CategorySummary> categoryBreakdown =
            expenseRepo.getCategoryBreakdownByProject(projectId).stream()
                .map(row -> ProjectExpenseStatsResponse.CategorySummary.builder()
                    .category(row[0] != null ? row[0].toString() : "Unknown")
                    .totalAmount(row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO)
                    .count(row[2] != null ? ((Number) row[2]).longValue() : 0L)
                    .build())
                .collect(Collectors.toList());

        return ProjectExpenseStatsResponse.builder()
            .projectId(projectId)
            .totalExpenses(totalExpenses.add(pendingExpenses))
            .approvedExpenses(totalExpenses)
            .pendingExpenses(pendingExpenses)
            .travelAndSiteVisit(travelSite)
            .totalCommission(commission)
            .pendingApprovals(pendingCount)
            .approvedThisMonth(approvedMonth)
            .totalAdvances(totalAdv)
            .unsettledAdvances(unsettledAdv)
            .userBreakdown(userBreakdown)
            .categoryBreakdown(categoryBreakdown)
            .build();
    }

    @Transactional(readOnly = true)
    public ProjectExpenseStatsResponse getGlobalStats(String groupName, String subGroupName) {
        return getGlobalStats(groupName, subGroupName, null, null, null, null, null, null, null, null);
    }

    public ProjectExpenseStatsResponse getGlobalStats(
            String groupName, String subGroupName, String projectId,
            String status, String paymentMode,
            java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
            String search, Long createdBy) {
        return getGlobalStats(groupName, subGroupName, projectId, status, paymentMode,
                dateFrom, dateTo, search, createdBy, null);
    }

    public ProjectExpenseStatsResponse getGlobalStats(
            String groupName, String subGroupName, String projectId,
            String status, String paymentMode,
            java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
            String search, Long viewerUserId, List<Long> teamMemberIds) {
        // Delegate to the access-list-aware overload with no project list
        return getGlobalStatsInternal(groupName, subGroupName, projectId, status, paymentMode,
                dateFrom, dateTo, search, viewerUserId, teamMemberIds, null);
    }

    /**
     * Main internal stats method — three access paths mirror getExpenses exactly:
     *   1. accessibleProjectIds != null  → non-admin with project grants
     *   2. viewerUserId == null          → admin/bypass, full scope
     *   3. viewerUserId != null          → fallback, own records only
     */
    private ProjectExpenseStatsResponse getGlobalStatsInternal(
            String groupName, String subGroupName, String projectId,
            String status, String paymentMode,
            java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
            String search, Long viewerUserId, List<Long> teamMemberIds,
            List<String> accessibleProjectIds) {

        String gn  = blankToNull(groupName);
        String sgn = blankToNull(subGroupName);
        String pid = blankToNull(projectId);
        String pm  = blankToNull(paymentMode);
        String src = blankToNull(search);
        String st  = (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) ? null : status;

        BigDecimal approved, pending, travelSite, commission;
        Long       pendingCnt;

        if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
            // ── Path 1: project-grant scoped ──────────────────────────────────
            if (st != null) {
                if ("Approved".equalsIgnoreCase(st)) {
                    approved   = safe(expenseRepo.sumApprovedByProjects(accessibleProjectIds, gn, sgn, pm, dateFrom, dateTo, src));
                    pending    = BigDecimal.ZERO; pendingCnt = 0L;
                } else if ("Pending".equalsIgnoreCase(st)) {
                    approved   = BigDecimal.ZERO;
                    pending    = safe(expenseRepo.sumPendingByProjects(accessibleProjectIds, gn, sgn, pm, dateFrom, dateTo, src));
                    pendingCnt = expenseRepo.countPendingByProjects(accessibleProjectIds, gn, sgn, dateFrom, dateTo, src);
                } else {
                    approved = BigDecimal.ZERO; pending = BigDecimal.ZERO; pendingCnt = 0L;
                }
            } else {
                approved   = safe(expenseRepo.sumApprovedByProjects(accessibleProjectIds, gn, sgn, pm, dateFrom, dateTo, src));
                pending    = safe(expenseRepo.sumPendingByProjects(accessibleProjectIds, gn, sgn, pm, dateFrom, dateTo, src));
                pendingCnt = expenseRepo.countPendingByProjects(accessibleProjectIds, gn, sgn, dateFrom, dateTo, src);
            }
            if (st == null || "Approved".equalsIgnoreCase(st) || "Pending".equalsIgnoreCase(st)) {
                travelSite = safe(expenseRepo.sumTravelAndSiteVisitByProjects(accessibleProjectIds, gn, sgn, pm, dateFrom, dateTo, src));
                commission = safe(expenseRepo.sumCommissionByProjects(accessibleProjectIds, gn, sgn, pm, dateFrom, dateTo, src));
            } else {
                travelSite = BigDecimal.ZERO; commission = BigDecimal.ZERO;
            }

        } else {
            // ── Path 2 (admin: viewerUserId=null) or Path 3 (fallback: viewerUserId=userId) ──
            List<Long> teamIds = (teamMemberIds != null && !teamMemberIds.isEmpty())
                    ? teamMemberIds : java.util.List.of(-1L);

            if (st != null) {
                if ("Approved".equalsIgnoreCase(st)) {
                    approved   = safe(expenseRepo.sumAllApproved(gn, sgn, pid, pm, dateFrom, dateTo, src, viewerUserId, teamIds));
                    pending    = BigDecimal.ZERO; pendingCnt = 0L;
                } else if ("Pending".equalsIgnoreCase(st)) {
                    approved   = BigDecimal.ZERO;
                    pending    = safe(expenseRepo.sumAllPending(gn, sgn, pid, pm, dateFrom, dateTo, src, viewerUserId, teamIds));
                    pendingCnt = expenseRepo.countAllPending(gn, sgn, pid, dateFrom, dateTo, src, viewerUserId, teamIds);
                } else {
                    approved = BigDecimal.ZERO; pending = BigDecimal.ZERO; pendingCnt = 0L;
                }
            } else {
                approved   = safe(expenseRepo.sumAllApproved(gn, sgn, pid, pm, dateFrom, dateTo, src, viewerUserId, teamIds));
                pending    = safe(expenseRepo.sumAllPending(gn, sgn, pid, pm, dateFrom, dateTo, src, viewerUserId, teamIds));
                pendingCnt = expenseRepo.countAllPending(gn, sgn, pid, dateFrom, dateTo, src, viewerUserId, teamIds);
            }
            if (st == null || "Approved".equalsIgnoreCase(st) || "Pending".equalsIgnoreCase(st)) {
                travelSite = safe(expenseRepo.sumAllTravelAndSiteVisit(gn, sgn, pid, pm, dateFrom, dateTo, src, viewerUserId, teamIds));
                commission = safe(expenseRepo.sumAllCommission(gn, sgn, pid, pm, dateFrom, dateTo, src, viewerUserId, teamIds));
            } else {
                travelSite = BigDecimal.ZERO; commission = BigDecimal.ZERO;
            }
        }

        BigDecimal totalExpenses = approved.add(pending);
        BigDecimal advances = safe(expenseRepo.sumAllAdvances(gn, sgn));

        return ProjectExpenseStatsResponse.builder()
            .totalExpenses(totalExpenses)
            .approvedExpenses(approved)
            .pendingExpenses(pending)
            .pendingApprovals(pendingCnt)
            .travelAndSiteVisit(travelSite)
            .totalCommission(commission)
            .totalAdvances(advances)
            .build();
    }

    public ProjectExpenseStatsResponse getGlobalStatsWithVisibility(
            String groupName, String subGroupName,
            Long viewerUserId, List<Long> teamMemberIds) {
        return getGlobalStatsInternal(groupName, subGroupName, null, null, null, null, null, null,
                viewerUserId, teamMemberIds, null);
    }

    /** Public entry point called from controller — includes accessibleProjectIds path. */
    public ProjectExpenseStatsResponse getGlobalStatsForAccess(
            String groupName, String subGroupName, String projectId,
            String status, String paymentMode,
            java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
            String search, Long viewerUserId, List<Long> teamMemberIds,
            List<String> accessibleProjectIds) {
        return getGlobalStatsInternal(groupName, subGroupName, projectId, status, paymentMode,
                dateFrom, dateTo, search, viewerUserId, teamMemberIds, accessibleProjectIds);
    }

    /** Returns a zeroed-out stats response — used when a user has no access to the requested project. */
    public ProjectExpenseStatsResponse emptyStats() {
        return ProjectExpenseStatsResponse.builder()
            .totalExpenses(BigDecimal.ZERO)
            .approvedExpenses(BigDecimal.ZERO)
            .pendingExpenses(BigDecimal.ZERO)
            .pendingApprovals(0L)
            .travelAndSiteVisit(BigDecimal.ZERO)
            .totalCommission(BigDecimal.ZERO)
            .totalAdvances(BigDecimal.ZERO)
            .build();
    }

    // =========================================================================
    // HISTORY  (unchanged)
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<ProjectExpenseHistory> getHistory(
            String projectId, String groupName, String subGroupName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (projectId != null && !projectId.isBlank()) {
            return historyRepo.findByProjectIdOrderByCreatedAtDesc(projectId, pageable);
        }
        return historyRepo.findByProjectIdOrderByCreatedAtDesc(null, pageable);
    }

    @Transactional(readOnly = true)
    public List<ProjectExpenseHistory> getExpenseHistory(Long expenseId) {
        return historyRepo.findByReferenceTypeAndReferenceIdOrderByCreatedAtDesc(
            ProjectExpenseHistory.ReferenceType.EXPENSE, expenseId);
    }

    // =========================================================================
    // DASHBOARD INTEGRATION  (unchanged)
    // =========================================================================

    @Transactional(readOnly = true)
    public ExpenseDashboardBlock getExpenseDashboardBlock(String projectId) {
        ProjectExpenseStatsResponse stats = getExpenseStats(projectId);
        List<ProjectExpense> recent = expenseRepo
            .findTop5ByProjectIdAndIsDeletedFalseOrderByTripDateDesc(projectId);

        return ExpenseDashboardBlock.builder()
            .totalExpenses(stats.getTotalExpenses())
            .approvedExpenses(stats.getApprovedExpenses())
            .pendingExpenses(stats.getPendingExpenses())
            .pendingApprovals(stats.getPendingApprovals())
            .travelAndSiteVisit(stats.getTravelAndSiteVisit())
            .totalCommission(stats.getTotalCommission())
            .approvedThisMonth(stats.getApprovedThisMonth())
            .totalAdvances(stats.getTotalAdvances())
            .unsettledAdvances(stats.getUnsettledAdvances())
            .userBreakdown(stats.getUserBreakdown())
            .categoryBreakdown(stats.getCategoryBreakdown())
            .recentExpenses(recent.stream().map(this::toExpenseResponse).collect(Collectors.toList()))
            .build();
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private void updateProjectExpenseStats(String projectId) {
        try {
            BigDecimal approved     = safe(expenseRepo.sumApprovedByProject(projectId));
            Long       pendingCnt   = expenseRepo.countPending(projectId);
            BigDecimal travel       = safe(expenseRepo.sumTravelAndSiteVisit(projectId));
            BigDecimal commission   = safe(expenseRepo.sumCommission(projectId));
            BigDecimal totalAdv     = safe(advanceRepo.sumTotalByProject(projectId));
            BigDecimal unsettledAdv = safe(advanceRepo.sumUnsettledByProject(projectId));
            BigDecimal pending      = safe(expenseRepo.sumPendingByProject(projectId));

            projectRepo.updateExpenseStats(
                projectId, approved, pendingCnt.intValue(),
                travel, commission, totalAdv, unsettledAdv, pending);
        } catch (Exception e) {
            log.error("Failed to update project expense stats for {}: {}", projectId, e.getMessage());
        }
    }

    private void logHistory(ProjectExpenseHistory.ReferenceType type, Long refId,
            String projectId, String action, Long userId, String userName,
            String oldStatus, String newStatus,
            BigDecimal oldAmount, BigDecimal newAmount,
            String description, Object snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            historyRepo.save(ProjectExpenseHistory.builder()
                .referenceType(type)
                .referenceId(refId)
                .projectId(projectId)
                .action(action)
                .changedByUserId(userId)
                .changedByName(userName)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .oldAmount(oldAmount)
                .newAmount(newAmount)
                .changeDescription(description)
                .snapshotJson(json)
                .build());
        } catch (Exception e) {
            log.warn("History logging failed: {}", e.getMessage());
        }
    }

    private Sort buildSort(String sortBy, String dir) {
        // "Latest record at top" — default to creation time, newest first.
        if (sortBy == null || sortBy.isBlank()) sortBy = "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(dir)
            ? Sort.Direction.ASC : Sort.Direction.DESC;
        Map<String, String> fieldMap = Map.of(
            "date",        "tripDate",
            "tripDate",    "tripDate",
            "createdAt",   "createdAt",
            "amount",      "amount",
            "category",    "category",
            "status",      "status",
            "paymentMode", "paymentMode",
            "paidByName",  "paidByName"
        );
        String field = fieldMap.getOrDefault(sortBy, "createdAt");
        // Always tiebreak on id in the SAME direction, so records sharing a
        // timestamp (e.g. several created in one bulk submit) stay in a stable,
        // strictly monotonic order — newest-inserted first when descending.
        return Sort.by(direction, field).and(Sort.by(direction, "id"));
    }

    /**
     * Derives the expense code from the DB-generated primary key.
     * Format: SESOLA/{YEAR}/{SEQUENCE}
     *   - YEAR     = current system year at record creation time
     *   - SEQUENCE = auto-increment id, left-padded to at least 4 digits
     * Examples: SESOLA/2026/0001, SESOLA/2026/0040, SESOLA/2026/0125
     *
     * Because this value is derived deterministically from the unique id,
     * no retry loop or random generation is needed. The unique DB constraint
     * on expense_code remains the final safety net.
     */
    private String buildExpenseCode(Long id) {
        int year = LocalDate.now().getYear();
        // Left-pad to 4 digits minimum; wider automatically if id >= 10000
        String seq = String.format("%04d", id);
        return "SESOLA/" + year + "/" + seq;
    }

    private String generateAdvanceCode() {
        String base = "ADV-" + LocalDate.now().getYear() + "-";
        String code;
        int attempts = 0;
        do {
            code = base + String.format("%05d", (long)(Math.random() * 90000) + 10000);
        } while (advanceRepo.existsByAdvanceCode(code) && ++attempts < 20);
        return code;
    }

    private ProjectExpense findExpenseOrThrow(Long id) {
        return expenseRepo.findById(id)
            .filter(e -> !Boolean.TRUE.equals(e.getIsDeleted()))
            .orElseThrow(() -> new EntityNotFoundException("Expense not found: " + id));
    }

    private ProjectExpenseResponse.ApprovalStage stageOf(
            String status, Long byId, String byName, LocalDateTime at, String remarks) {
        return ProjectExpenseResponse.ApprovalStage.builder()
            .status(status)
            .byId(byId)
            .byName(byName)
            .at(at)
            .remarks(remarks)
            .build();
    }

    private ProjectExpenseResponse toExpenseResponse(ProjectExpense e) {
        List<ProjectExpenseResponse.ExpenseItemResponse> items =
            e.getExpenseItems() == null ? List.of() :
            e.getExpenseItems().stream()
                .map(i -> ProjectExpenseResponse.ExpenseItemResponse.builder()
                    .id(i.getId())
                    .category(i.getCategory())
                    .amount(i.getAmount())
                    .paymentMode(i.getPaymentMode())
                    .description(i.getDescription())
                    .build())
                .collect(Collectors.toList());

        String resolvedProjectName = null;
        if (e.getProjectId() != null && !e.getProjectId().isBlank()) {
            resolvedProjectName = projectRepo.findByProjectUniqueId(e.getProjectId())
                .map(p -> p.getProjectName())
                .orElse(null);
        }

        boolean hasBill = billRepo.existsByExpenseId(e.getId());

        return ProjectExpenseResponse.builder()
            .id(e.getId())
            .expenseCode(e.getExpenseCode())
            .projectId(e.getProjectId())
            .projectName(resolvedProjectName)
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .tripDate(e.getTripDate())
            .tripReason(e.getTripReason())
            .totalAmount(e.getTotalAmount())
            .paidByUserId(e.getPaidByUserId())
            .paidByName(e.getPaidByName())
            .status(e.getStatus())
            .receiptUrl(e.getReceiptUrl())
            .hasBill(hasBill)
            .approvalStage(e.getApprovalStage())
            .reportingManagerId(e.getReportingManagerId())
            .reportingManagerName(e.getReportingManagerName())
            .stage1(stageOf(e.getStage1Status(), e.getStage1ById(), e.getStage1ByName(), e.getStage1At(), e.getStage1Remarks()))
            .stage2(stageOf(e.getStage2Status(), e.getStage2ById(), e.getStage2ByName(), e.getStage2At(), e.getStage2Remarks()))
            .stage3(stageOf(e.getStage3Status(), e.getStage3ById(), e.getStage3ByName(), e.getStage3At(), e.getStage3Remarks()))
            .paymentStatus(e.getPaymentStatus())
            .paidByExecId(e.getPaidByExecId())
            .paidByExecName(e.getPaidByExecName())
            .paidAt(e.getPaidAt())
            .expenseItems(items)
            .createdAt(e.getCreatedAt())
            .updatedAt(e.getUpdatedAt())
            .build();
    }

    private ProjectAdvanceResponse toAdvanceResponse(ProjectAdvance a) {
        List<ProjectAdvanceResponse.PaymentDetail> payments = a.getPayments() == null ? List.of() :
            a.getPayments().stream()
                .map(p -> ProjectAdvanceResponse.PaymentDetail.builder()
                    .id(p.getId())
                    .advanceNumber(p.getAdvanceNumber())
                    .amount(p.getAmount())
                    .paymentMode(p.getPaymentMode())
                    .paymentDate(p.getPaymentDate())
                    .notes(p.getNotes())
                    .build())
                .collect(Collectors.toList());

        return ProjectAdvanceResponse.builder()
            .id(a.getId())
            .advanceCode(a.getAdvanceCode())
            .projectId(a.getProjectId())
            .groupName(a.getGroupName())
            .subGroupName(a.getSubGroupName())
            .advanceDate(a.getAdvanceDate())
            .expectedTripDate(a.getExpectedTripDate())
            .tripPurpose(a.getTripPurpose())
            .requestedByUserId(a.getRequestedByUserId())
            .requestedByName(a.getRequestedByName())
            .approvedByUserId(a.getApprovedByUserId())
            .approvedByName(a.getApprovedByName())
            .totalAdvanceAmount(a.getTotalAdvanceAmount())
            .status(a.getStatus())
            .relatedExpenseId(a.getRelatedExpenseId())
            .advancePayments(payments)
            .createdAt(a.getCreatedAt())
            .build();
    }

    // ── normalization + error helpers ─────────────────────────────────────────
    private String norm(String s) { return s == null ? "" : s.toUpperCase().replaceAll("[^A-Z]", ""); }

    // =========================================================================
    // APPROVAL-STAGE EMAIL NOTIFICATIONS
    //
    // Recipients are the INTENDED approver for the current stage — deliberately
    // NARROWER than the approval-permission check (which also lets SUPERADMIN /
    // ADMIN override). We do NOT email overriders; only the person expected to
    // act. Addresses come straight from users.email.
    //   MANAGER → the expense's reportingManagerId user.
    //   CFO     → active users whose normalized role = ACCOUNTSCFO
    //             OR normalized designation = CFO.
    //   FINAL   → active users whose normalized role = ACCOUNTSMANAGER
    //             OR normalized designation = ACCOUNTSMANAGER ("Accounts Manager").
    // Superadmins are excluded even though they can approve.
    // =========================================================================

    /** Resolve the email recipients for whatever stage the expense is now waiting on. */
    private List<UsersEntity> resolveStageApprovers(ProjectExpense e) {
        String stage = e.getApprovalStage();
        if (stage == null) return List.of();
        switch (stage) {
            case "MANAGER": {
                if (e.getReportingManagerId() == null) return List.of();
                return usersRepo.findById(e.getReportingManagerId())
                    .filter(u -> u.getIs_active() != null && u.getIs_active() == 1L)
                    .map(List::of).orElseGet(List::of);
            }
            case "CFO":
                return matchActive(u ->
                    norm(u.getRole()).equals("ACCOUNTSCFO")
                    || norm(u.getDesignation()).equals("CFO"));
            case "FINAL":
                return matchActive(u ->
                    norm(u.getRole()).equals("ACCOUNTSMANAGER")
                    || norm(u.getDesignation()).equals("ACCOUNTSMANAGER"));
            default:
                return List.of(); // COMPLETED / REJECTED — no pending approver
        }
    }

    /** Active, non-superadmin users matching a predicate, with a non-blank email. */
    private List<UsersEntity> matchActive(java.util.function.Predicate<UsersEntity> p) {
        return usersRepo.findAllActiveUsers().stream()
            .filter(u -> !norm(u.getRole()).equals("SUPERADMIN"))
            .filter(p)
            .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
            .collect(Collectors.toList());
    }

    private String stageApproverLabel(String stage) {
        if (stage == null) return "Approver";
        switch (stage) {
            case "MANAGER": return "Reporting Manager";
            case "CFO":     return "Chief Financial Officer";
            case "FINAL":   return "Accounts Manager";
            default:        return "Approver";
        }
    }

    /**
     * Email the current pending approver(s) that an expense awaits their action.
     * Never throws into the caller — a mail failure must not roll back an approval
     * or a submission. Runs best-effort; MailService.sendEmail is itself @Async.
     */
    private void notifyPendingApprover(ProjectExpense e) {
        try {
            String stage = e.getApprovalStage();
            if (stage == null || "COMPLETED".equals(stage) || "REJECTED".equals(stage)) return;
            List<UsersEntity> approvers = resolveStageApprovers(e);
            if (approvers.isEmpty()) {
                log.warn("Expense {} at stage {} has no resolvable approver to notify.",
                    e.getExpenseCode(), stage);
                return;
            }
            String subject = "[Action needed] Expense " + e.getExpenseCode()
                + " awaits your approval (" + stageApproverLabel(stage) + ")";
            String noteMsg = "Expense " + e.getExpenseCode() + " (" + projectLabel(e.getProjectId())
                + ") is pending your approval as " + stageApproverLabel(stage) + ".";
            for (UsersEntity approver : approvers) {
                // In-app notification — one per approver, mirrors the email.
                // Sent regardless of whether the user has an email on file.
                safeNotify(approver.getId(), "Expense pending approval", noteMsg,
                    e.getId(), NotificationConstants.Type.EXPENSE_PENDING_APPROVAL);
                if (approver.getEmail() != null && !approver.getEmail().isBlank()) {
                    mailService.sendEmail(approver.getEmail(), subject,
                        buildApproverEmailBody(e, approver, stage));
                }
            }
        } catch (Exception ex) {
            log.error("Failed to notify approver for expense {}: {}",
                e.getExpenseCode(), ex.getMessage());
        }
    }

    /** Notify the original submitter once the chain finishes (approved or rejected). */
    private void notifySubmitterFinal(ProjectExpense e, boolean rejected) {
        try {
            if (e.getCreatedBy() == null) return;
            UsersEntity submitter = usersRepo.findById(e.getCreatedBy()).orElse(null);
            if (submitter == null) return;
            String verdict = rejected ? " was rejected" : " was fully approved";
            // In-app notification first — independent of email availability.
            safeNotify(submitter.getId(),
                rejected ? "Expense rejected" : "Expense approved",
                "Expense " + e.getExpenseCode() + " (" + projectLabel(e.getProjectId()) + ")" + verdict + ".",
                e.getId(),
                rejected ? NotificationConstants.Type.EXPENSE_REJECTED
                         : NotificationConstants.Type.EXPENSE_APPROVED);
            if (submitter.getEmail() != null && !submitter.getEmail().isBlank()) {
                mailService.sendEmail(submitter.getEmail(),
                    "Expense " + e.getExpenseCode() + verdict,
                    buildSubmitterEmailBody(e, submitter, rejected));
            }
        } catch (Exception ex) {
            log.error("Failed to notify submitter for expense {}: {}",
                e.getExpenseCode(), ex.getMessage());
        }
    }

    /**
     * Create a single in-app notification, swallowing any failure so it can
     * never roll back the surrounding approval transaction. The notification
     * service already null-guards a missing userId and pushes over WebSocket.
     */
    private void safeNotify(Long userId, String title, String message, Long refId, String type) {
        try {
            if (userId == null) return;
            notificationService.createNotification(
                userId, title, message, NotificationConstants.Module.EXPENSE, refId, type);
        } catch (Exception ex) {
            log.error("In-app notification failed (user {}, type {}): {}", userId, type, ex.getMessage());
        }
    }

    private String buildApproverEmailBody(ProjectExpense e, UsersEntity approver, String stage) {
        String amount = e.getTotalAmount() == null ? "-" : e.getTotalAmount().toPlainString();
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#1f2937\">"
            + "<p>Dear " + safe(approver.getName()) + ",</p>"
            + "<p>An expense is pending your approval as <b>" + stageApproverLabel(stage) + "</b>.</p>"
            + "<table style=\"border-collapse:collapse\">"
            + row("Expense Code", safe(e.getExpenseCode()))
            + row("Project", safe(projectLabel(e.getProjectId())))
            + row("Submitted by", safe(e.getPaidByName() != null ? e.getPaidByName() : e.getReportingManagerName()))
            + row("Trip date", e.getTripDate() == null ? "-" : e.getTripDate().toString())
            + row("Amount", "INR " + amount)
            + row("Reason", safe(e.getTripReason()))
            + "</table>"
            + "<p>Please log in to the SESOLA CRM portal to review and take action:<br>"
            + "<a href=\"https://crm.sesolaenergy.com\">crm.sesolaenergy.com</a></p>"
            + "<p style=\"color:#6b7280;font-size:12px\">This is an automated notification.</p>"
            + "</div>";
    }

    private String buildSubmitterEmailBody(ProjectExpense e, UsersEntity submitter, boolean rejected) {
        String amount = e.getTotalAmount() == null ? "-" : e.getTotalAmount().toPlainString();
        String verdict = rejected
            ? "<span style=\"color:#b91c1c\"><b>rejected</b></span>"
            : "<span style=\"color:#15803d\"><b>fully approved</b></span>";
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#1f2937\">"
            + "<p>Dear " + safe(submitter.getName()) + ",</p>"
            + "<p>Your expense " + verdict + ".</p>"
            + "<table style=\"border-collapse:collapse\">"
            + row("Expense Code", safe(e.getExpenseCode()))
            + row("Project", safe(projectLabel(e.getProjectId())))
            + row("Amount", "INR " + amount)
            + "</table>"
            + "<p>View in the SESOLA CRM portal: "
            + "<a href=\"https://crm.sesolaenergy.com\">crm.sesolaenergy.com</a></p>"
            + "<p style=\"color:#6b7280;font-size:12px\">This is an automated notification.</p>"
            + "</div>";
    }

    /**
     * Human-readable project label for emails: the project name if resolvable,
     * otherwise the raw project id. projectId holds the unique id string
     * (e.g. "PROJ-2026-0005"), so we look up by projectUniqueId. Falls back to
     * the id when the project is missing or its name is blank.
     */
    private String projectLabel(String projectId) {
        if (projectId == null || projectId.isBlank()) return "-";
        try {
            String name = projectRepo.findByProjectUniqueId(projectId)
                .map(ProjectEntity::getProjectName).orElse(null);
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ex) {
            log.warn("Could not resolve project name for {}: {}", projectId, ex.getMessage());
        }
        return projectId;
    }

    private String row(String k, String v) {
        return "<tr><td style=\"padding:4px 12px 4px 0;color:#6b7280\">" + k
            + "</td><td style=\"padding:4px 0\"><b>" + v + "</b></td></tr>";
    }
    private String safe(String s) { return s == null ? "-" : s.replace("<", "&lt;").replace(">", "&gt;"); }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
    private ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }

    private BigDecimal safe(BigDecimal val)  { return val != null ? val : BigDecimal.ZERO; }
    private String nullIfAll(String v)        { return (v == null || "all".equalsIgnoreCase(v) || v.isBlank()) ? null : v; }
    private String blankToNull(String v)      { return (v == null || v.isBlank()) ? null : v; }
}