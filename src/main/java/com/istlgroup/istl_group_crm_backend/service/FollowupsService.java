package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;

import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Module;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Type;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;

@Service
public class FollowupsService {

    @Autowired
    private FollowupsRepo followupsRepo;

    @Autowired
    private LeadsRepo leadsRepo;

    @Autowired
    private CustomersRepo customersRepo;

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private LeadHistoryService leadHistoryService;

    @Autowired
    private MailService mailServie;

    @Autowired
    private NotificationService notificationService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─────────────────────────────────────────────────────────────────────────
    // SERVER-SIDE PAGINATED FETCH (main Follow-ups page)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a page of follow-ups + KPI counts, all filtered server-side.
     *
     * @param userId          null for admin/superadmin (no user restriction)
     * @param groupName       optional group filter
     * @param subGroupName    optional sub-group filter
     * @param status          optional status filter
     * @param priority        optional priority filter
     * @param followupType    optional type filter
     * @param assignedToFilter optional assignee ID filter
     * @param fromDate        optional start of date range (scheduledAt)
     * @param toDate          optional end of date range (scheduledAt)
     * @param searchTerm      optional search string
     * @param page            0-based page number
     * @param pageSize        records per page
     * @return Map with keys: data (List<FollowupWrapper>), totalCount (long), kpis (Map)
     */
    public Map<String, Object> getFollowupsPaged(
            Long userId,
            String groupName,
            String subGroupName,
            String status,
            String priority,
            String followupType,
            Long assignedToFilter,
            String fromDateStr,
            String toDateStr,
            String searchTerm,
            int page,
            int pageSize) {

        // ── Parse date range ────────────────────────────────────────────────
        LocalDateTime fromDate = null;
        LocalDateTime toDate   = null;
        if (fromDateStr != null && !fromDateStr.isBlank()) {
            fromDate = LocalDate.parse(fromDateStr).atStartOfDay();
        }
        if (toDateStr != null && !toDateStr.isBlank()) {
            toDate = LocalDate.parse(toDateStr).atTime(LocalTime.MAX);
        }

        // ── Normalise search term for LIKE query ────────────────────────────
        String likeSearch = (searchTerm != null && !searchTerm.isBlank())
                ? "%" + searchTerm.toLowerCase() + "%" : null;

        // ── Pageable: newest records first (by id DESC) ─────────────────────
        PageRequest pageable = PageRequest.of(
                Math.max(0, page - 1),   // frontend sends 1-based page
                pageSize > 0 ? pageSize : 10,
                Sort.by("id").descending()
        );

        // ── Data page ───────────────────────────────────────────────────────
        Page<FollowupsEntity> resultPage = followupsRepo.findPagedByFilters(
                userId, groupName, subGroupName,
                status, priority, followupType, assignedToFilter,
                fromDate, toDate, likeSearch,
                pageable
        );

        List<FollowupWrapper> data = resultPage.getContent()
                .stream().map(this::convertToWrapper).collect(Collectors.toList());

        // ── KPIs (always based on full scope — no active filter applied) ────
        LocalDateTime now   = LocalDateTime.now();
        long totalKpi       = followupsRepo.countTotal(userId, groupName, subGroupName);
        long pendingKpi     = followupsRepo.countByStatus(userId, groupName, subGroupName, "Pending");
        long completedKpi   = followupsRepo.countByStatus(userId, groupName, subGroupName, "Completed");
        long overdueKpi     = followupsRepo.countOverdue(userId, groupName, subGroupName, now);
        long todayKpi       = followupsRepo.countDueToday(userId, groupName, subGroupName, now);

        Map<String, Long> kpis = new HashMap<>();
        kpis.put("total",     totalKpi);
        kpis.put("pending",   pendingKpi);
        kpis.put("completed", completedKpi);
        kpis.put("overdue",   overdueKpi);
        kpis.put("today",     todayKpi);

        // ── Result map ──────────────────────────────────────────────────────
        Map<String, Object> result = new HashMap<>();
        result.put("data",        data);
        result.put("totalCount",  resultPage.getTotalElements());
        result.put("totalPages",  resultPage.getTotalPages());
        result.put("currentPage", page);
        result.put("pageSize",    pageSize);
        result.put("kpis",        kpis);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────

    public FollowupWrapper createFollowup(FollowupRequestWrapper request, Long createdBy)
            throws CustomException {

        try {
            if (request.getAssignedTo() != null && !request.getAssignedTo().equals(createdBy)) {
                sendFollwUpMail(request, createdBy);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        LocalDateTime scheduledAt = LocalDateTime.parse(request.getScheduledAt(), DATE_FORMATTER);

        FollowupsEntity followup = new FollowupsEntity();
        followup.setRelatedType(request.getRelatedType());
        followup.setRelatedId(request.getRelatedId());
        followup.setLeadId(request.getLeadId());
        followup.setCustomerId(request.getCustomerId());
        followup.setProjectId(request.getProjectId());
        followup.setGroupName(request.getGroupName());
        followup.setSubGroupName(request.getSubGroupName());
        followup.setFollowupType(request.getFollowupType() != null ? request.getFollowupType() : "Call");
        followup.setScheduledAt(scheduledAt);
        followup.setCreatedBy(createdBy);
        followup.setAssignedTo(request.getAssignedTo() != null ? request.getAssignedTo() : createdBy);
        followup.setStatus(request.getStatus() != null ? request.getStatus() : "Pending");
        followup.setPriority(request.getPriority() != null ? request.getPriority() : "Medium");
        followup.setNotes(request.getNotes());

        // Store outcome on create (direct interactions are created as Completed with outcome)
        followup.setOutcome(request.getOutcome());

        // If status is already Completed at create time (direct interaction), set completedAt
        if ("Completed".equals(followup.getStatus())) {
            followup.setCompletedAt(scheduledAt);
        }

        FollowupsEntity saved = followupsRepo.save(followup);

        // NOTIFICATION: new follow-up assigned (skip self-assignment)
        try {
            if (saved.getAssignedTo() != null && !saved.getAssignedTo().equals(createdBy)) {
                notificationService.createNotification(
                    saved.getAssignedTo(),
                    "New follow-up assigned",
                    "A " + (saved.getFollowupType() != null ? saved.getFollowupType() : "")
                        + " follow-up has been assigned to you.",
                    Module.FOLLOWUP, saved.getId(), Type.FOLLOWUP_ASSIGNED);
            }
        } catch (Exception e) {
            System.err.println("[FollowupsService] notification error on create: " + e.getMessage());
        }

        // History entry
        if (request.getLeadId() != null) {
            try {
                boolean isDirect = "Completed".equals(request.getStatus())
                        && request.getOutcome() != null
                        && !request.getOutcome().isBlank();

                if (isDirect) {
                    String description = String.format(
                            "Direct %s recorded on %s — %s",
                            followup.getFollowupType(),
                            scheduledAt.format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")),
                            request.getOutcome().trim()
                    );
                    leadHistoryService.addHistory(
                            request.getLeadId(),
                            "DIRECT_INTERACTION",
                            null, null,
                            request.getNotes() != null ? request.getNotes().trim() : null,
                            description,
                            createdBy
                    );
                } else {
                    String description = String.format(
                            "Follow-up scheduled: %s on %s",
                            followup.getFollowupType(),
                            scheduledAt.format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm"))
                    );
                    leadHistoryService.addHistory(
                            request.getLeadId(),
                            "FOLLOWUP_ADDED",
                            null, null, null,
                            description,
                            createdBy
                    );
                }
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }

        return convertToWrapper(saved);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    public FollowupWrapper updateFollowup(Long followupId,
                                           FollowupRequestWrapper request,
                                           Long userId) throws CustomException {

        FollowupsEntity followup = followupsRepo.findById(followupId)
                .orElseThrow(() -> new CustomException("Follow-up not found"));

        Long previousAssignee = followup.getAssignedTo();

        try {
            Long newAssignedTo     = request.getAssignedTo();
            Long currentAssignedTo = followup.getAssignedTo();

            boolean assigneeIsChanging   = newAssignedTo != null
                    && !newAssignedTo.equals(currentAssignedTo);
            boolean assigneeIsNotUpdater = !newAssignedTo.equals(userId);

            if (assigneeIsChanging && assigneeIsNotUpdater) {
                sendFollwUpMailOnUpdate(request, followup, userId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (request.getFollowupType() != null) followup.setFollowupType(request.getFollowupType());
        if (request.getScheduledAt()  != null) followup.setScheduledAt(LocalDateTime.parse(request.getScheduledAt(), DATE_FORMATTER));
        if (request.getAssignedTo()   != null) followup.setAssignedTo(request.getAssignedTo());
        if (request.getPriority()     != null) followup.setPriority(request.getPriority());
        if (request.getNotes()        != null) followup.setNotes(request.getNotes());
        if (request.getOutcome()      != null) followup.setOutcome(request.getOutcome());

        if (request.getStatus() != null) {
            String oldStatus = followup.getStatus();
            followup.setStatus(request.getStatus());
            if ("Completed".equals(request.getStatus()) && !"Completed".equals(oldStatus)) {
                followup.setCompletedAt(LocalDateTime.now());
            }
        }

        FollowupsEntity updated = followupsRepo.save(followup);

        // NOTIFICATION: follow-up reassigned
        try {
            Long newAssignee = updated.getAssignedTo();
            boolean changed   = newAssignee != null && !newAssignee.equals(previousAssignee);
            boolean notSelf   = newAssignee != null && !newAssignee.equals(userId);
            if (changed && notSelf) {
                notificationService.createNotification(
                    newAssignee,
                    "Follow-up reassigned to you",
                    "A " + (updated.getFollowupType() != null ? updated.getFollowupType() : "")
                        + " follow-up has been reassigned to you.",
                    Module.FOLLOWUP, updated.getId(), Type.FOLLOWUP_REASSIGNED);
            }
        } catch (Exception e) {
            System.err.println("[FollowupsService] notification error on update: " + e.getMessage());
        }

        if (followup.getLeadId() != null && "Completed".equals(request.getStatus())) {
            try {
                String description = String.format("Follow-up completed: %s", followup.getFollowupType());
                if (request.getOutcome() != null && !request.getOutcome().isEmpty()) {
                    description += " — " + request.getOutcome();
                }
                String notes = followup.getNotes() != null && !followup.getNotes().isBlank()
                        ? followup.getNotes().trim() : null;
                leadHistoryService.addHistory(followup.getLeadId(), "FOLLOWUP_COMPLETED",
                        null, null, notes, description, userId);
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }

        return convertToWrapper(updated);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EMAIL
    // ─────────────────────────────────────────────────────────────────────────

    public void sendFollwUpMail(FollowupRequestWrapper request, Long createdBy) {
        Long   assignedUserId = request.getAssignedTo();
        String email          = usersRepo.findUserMailWithUserId(assignedUserId);
        String name           = usersRepo.findUserNameWithUserId(assignedUserId);
        String fromName       = usersRepo.findUserNameWithUserId(createdBy);

        String leadDisplay     = resolveLeadName(request.getLeadId());
        String customerDisplay = resolveCustomerName(request.getCustomerId());

        String groupName = request.getGroupName()    != null ? request.getGroupName()    : "N/A";
        String subGroup  = request.getSubGroupName() != null ? request.getSubGroupName() : "N/A";
        String priority  = request.getPriority()     != null ? request.getPriority()     : "Medium";
        String status    = request.getStatus()       != null ? request.getStatus()       : "Pending";
        String notes     = request.getNotes()        != null ? request.getNotes()        : "";

        String body = buildEmailBody(
                name, fromName, request.getFollowupType(), request.getScheduledAt(),
                leadDisplay, customerDisplay, groupName, subGroup, priority, status, notes
        );

        mailServie.sendEmail(email, fromName, body);
    }

    public void sendFollwUpMailOnUpdate(FollowupRequestWrapper request,
                                        FollowupsEntity existingEntity,
                                        Long updatedBy) {
        Long   assignedUserId = request.getAssignedTo();
        String email          = usersRepo.findUserMailWithUserId(assignedUserId);
        String name           = usersRepo.findUserNameWithUserId(assignedUserId);
        String fromName       = usersRepo.findUserNameWithUserId(updatedBy);

        Long effectiveLeadId     = request.getLeadId()     != null ? request.getLeadId()     : existingEntity.getLeadId();
        Long effectiveCustomerId = request.getCustomerId() != null ? request.getCustomerId() : existingEntity.getCustomerId();

        String leadDisplay     = resolveLeadName(effectiveLeadId);
        String customerDisplay = resolveCustomerName(effectiveCustomerId);

        String groupName = request.getGroupName() != null
                ? request.getGroupName()
                : (existingEntity.getGroupName() != null ? existingEntity.getGroupName() : "N/A");

        String subGroup = request.getSubGroupName() != null
                ? request.getSubGroupName()
                : (existingEntity.getSubGroupName() != null ? existingEntity.getSubGroupName() : "N/A");

        String followupType = request.getFollowupType() != null
                ? request.getFollowupType() : existingEntity.getFollowupType();

        String scheduledAt = request.getScheduledAt() != null
                ? request.getScheduledAt()
                : existingEntity.getScheduledAt().format(DATE_FORMATTER);

        String priority = request.getPriority() != null
                ? request.getPriority()
                : (existingEntity.getPriority() != null ? existingEntity.getPriority() : "Medium");

        String status = request.getStatus() != null
                ? request.getStatus()
                : (existingEntity.getStatus() != null ? existingEntity.getStatus() : "Pending");

        String notes = request.getNotes() != null
                ? request.getNotes()
                : (existingEntity.getNotes() != null ? existingEntity.getNotes() : "");

        String body = buildEmailBody(
                name, fromName, followupType, scheduledAt,
                leadDisplay, customerDisplay, groupName, subGroup, priority, status, notes
        );

        mailServie.sendEmail(email, fromName, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveLeadName(Long leadId) {
        if (leadId == null) return "N/A";
        return leadsRepo.findById(leadId)
                .map(lead -> lead.getName() != null ? lead.getName() : "Lead #" + leadId)
                .orElse("N/A");
    }

    private String resolveCustomerName(Long customerId) {
        if (customerId == null) return "N/A";
        return customersRepo.findById(customerId)
                .map(c -> {
                    if (c.getName() != null && !c.getName().isBlank())
                        return c.getName();
                    if (c.getCompanyName() != null && !c.getCompanyName().isBlank())
                        return c.getCompanyName();
                    return "Customer #" + customerId;
                })
                .orElse("N/A");
    }

    private String buildEmailBody(String recipientName,
                                   String assignedByName,
                                   String followupType,
                                   String scheduledAt,
                                   String leadDisplay,
                                   String customerDisplay,
                                   String groupName,
                                   String subGroupName,
                                   String priority,
                                   String status,
                                   String notes) {
        return """
               <html>
                 <body style="margin:0;padding:0;background-color:#f4f6f8;font-family:Arial, sans-serif;">
                   <table width="100%%" cellpadding="0" cellspacing="0" style="padding:20px 0;">
                     <tr>
                       <td align="center">
                         <table width="600" cellpadding="0" cellspacing="0"
                                style="background:#ffffff;border-radius:8px;
                                       box-shadow:0 2px 8px rgba(0,0,0,0.08);padding:30px;">
                           <tr>
                             <td align="center" style="padding-bottom:20px;">
                               <h2 style="margin:0;color:#2E86C1;">New Follow-Up Assigned By %s</h2>
                             </td>
                           </tr>
                           <tr>
                             <td style="font-size:16px;color:#333;padding-bottom:15px;">
                               Hello <b>%s</b>,
                             </td>
                           </tr>
                           <tr>
                             <td style="font-size:15px;color:#555;padding-bottom:20px;">
                               You have a <b style="color:#2E86C1;">%s</b> scheduled on <b>%s</b>.
                             </td>
                           </tr>
                           <tr>
                             <td>
                               <table width="100%%" cellpadding="8" cellspacing="0"
                                      style="background:#f9fafb;border-radius:6px;font-size:14px;color:#444;">
                                 <tr><td width="40%%"><b>Lead</b></td><td>%s</td></tr>
                                 <tr><td><b>Customer</b></td><td>%s</td></tr>
                                 <tr><td><b>Group</b></td><td>%s</td></tr>
                                 <tr><td><b>Sub Group</b></td><td>%s</td></tr>
                                 <tr><td><b>Priority</b></td><td style="color:#d35400;"><b>%s</b></td></tr>
                                 <tr><td><b>Status</b></td><td style="color:#27ae60;"><b>%s</b></td></tr>
                               </table>
                             </td>
                           </tr>
                           <tr>
                             <td style="padding-top:20px;font-size:14px;color:#555;">
                               <b>Notes:</b>
                               <div style="margin-top:8px;padding:10px;background:#f1f1f1;border-radius:5px;">%s</div>
                             </td>
                           </tr>
                           <tr>
                             <td style="padding-top:30px;font-size:13px;color:#888;text-align:center;">
                               Regards,<br><b>SESOLA CRM System</b><br><br>
                               <a href="https://crm.sesolaenergy.com/"
                                  style="color:#2E86C1;text-decoration:none;font-size:12px;font-weight:600;">
                                 &#128279; Open CRM Portal
                               </a>
                             </td>
                           </tr>
                         </table>
                       </td>
                     </tr>
                   </table>
                 </body>
               </html>
               """.formatted(
                assignedByName,
                recipientName, followupType, scheduledAt,
                leadDisplay, customerDisplay,
                groupName, subGroupName,
                priority, status, notes
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QUERY METHODS (kept for other modules / tabs)
    // ─────────────────────────────────────────────────────────────────────────

    public List<FollowupWrapper> getFollowupsForLead(Long leadId) {
        return followupsRepo.findByLeadIdOrderByScheduledAtDesc(leadId)
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public List<FollowupWrapper> getFollowupsForCustomer(Long customerId) {
        return followupsRepo.findByCustomerIdOrderByScheduledAtDesc(customerId)
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public List<FollowupWrapper> getFollowupsForRelatedEntity(String relatedType, Long relatedId) {
        return followupsRepo.findByRelatedTypeAndRelatedIdOrderByScheduledAtDesc(relatedType, relatedId)
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public List<FollowupWrapper> getFollowupsForUser(Long userId) {
        return followupsRepo.findByAssignedToOrCreatedByOrderByScheduledAtDesc(userId)
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public List<FollowupWrapper> getFollowupsByUserAndFilters(Long userId, String groupName, String subGroupName) {
        return followupsRepo.findByUserAndFilters(userId, groupName, subGroupName)
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public List<FollowupWrapper> getPendingFollowupsForLead(Long leadId) {
        return followupsRepo.findPendingByLeadId(leadId)
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public boolean hasLeadPendingFollowups(Long leadId) {
        return followupsRepo.countPendingByLeadId(leadId) > 0;
    }

    public int getPendingFollowupsCountForLead(Long leadId) {
        return followupsRepo.countPendingByLeadId(leadId);
    }

    public List<FollowupWrapper> getOverdueFollowups() {
        return followupsRepo.findOverdueFollowups(LocalDateTime.now())
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public List<FollowupWrapper> getTodaysFollowups() {
        return followupsRepo.findTodaysFollowups(LocalDateTime.now())
                .stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    public void deleteFollowup(Long followupId, Long userId) throws CustomException {
        FollowupsEntity followup = followupsRepo.findById(followupId)
                .orElseThrow(() -> new CustomException("Follow-up not found"));
        followupsRepo.delete(followup);
        if (followup.getLeadId() != null) {
            try {
                String description = String.format("Follow-up deleted: %s", followup.getFollowupType());
                leadHistoryService.addHistory(followup.getLeadId(), "FOLLOWUP_DELETED",
                        null, null, null, description, userId);
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }
    }

    public List<FollowupWrapper> getAllFollowups() {
        return followupsRepo.findAll().stream()
                .map(this::convertToWrapper)
                .sorted((a, b) -> b.getScheduledAt().compareTo(a.getScheduledAt()))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENTITY → WRAPPER
    // ─────────────────────────────────────────────────────────────────────────

    private FollowupWrapper convertToWrapper(FollowupsEntity entity) {
        FollowupWrapper wrapper = new FollowupWrapper();
        wrapper.setId(entity.getId());
        wrapper.setRelatedType(entity.getRelatedType());
        wrapper.setRelatedId(entity.getRelatedId());
        wrapper.setLeadId(entity.getLeadId());
        wrapper.setCustomerId(entity.getCustomerId());
        wrapper.setProjectId(entity.getProjectId());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        wrapper.setFollowupType(entity.getFollowupType());
        wrapper.setScheduledAt(entity.getScheduledAt() != null ? entity.getScheduledAt().toString() : null);
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setAssignedTo(entity.getAssignedTo());
        wrapper.setStatus(entity.getStatus());
        wrapper.setPriority(entity.getPriority());
        wrapper.setCompletedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().toString() : null);
        wrapper.setNotes(entity.getNotes());
        wrapper.setOutcome(entity.getOutcome());
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);

        if (entity.getCreatedBy() != null) {
            usersRepo.findById(entity.getCreatedBy())
                    .ifPresent(user -> wrapper.setCreatedByName(user.getName()));
        }
        if (entity.getAssignedTo() != null) {
            usersRepo.findById(entity.getAssignedTo())
                    .ifPresent(user -> wrapper.setAssignedToName(user.getName()));
        }
        if (entity.getLeadId() != null) {
            leadsRepo.findById(entity.getLeadId()).ifPresent(lead -> {
                wrapper.setLeadCode(lead.getLeadCode());
                wrapper.setLeadName(lead.getName());
                wrapper.setLeadPhone(lead.getPhone());
                wrapper.setLeadEmail(lead.getEmail());
                wrapper.setLeadStatus(lead.getStatus());
            });
        }
        if (entity.getCustomerId() != null) {
            customersRepo.findById(entity.getCustomerId()).ifPresent(c -> {
                String displayName = (c.getCompanyName() != null && !c.getCompanyName().isBlank())
                        ? c.getCompanyName()
                        : (c.getName() != null ? c.getName() : "Customer #" + entity.getCustomerId());
                String code = (c.getCustomerCode() != null) ? c.getCustomerCode() : "";
                wrapper.setCustomerCode(code + " — " + displayName);
            });
        }

        return wrapper;
    }
}