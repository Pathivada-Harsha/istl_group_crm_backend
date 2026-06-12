package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LeadAccessEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TelecallerDashboardStats;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TelecallerLeadView;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TelecallerStatusUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TelecallerLeadService {

    private static final List<String> VALID_STATUSES =
            Arrays.asList("NEW", "INTERESTED", "NOT_INTERESTED", "NOT_RESPONDED", "KEEP_IN_VIEW");

    // Main lead status values that belong to BD+ pipeline — telecaller cannot
    // update these and leads at these stages are hidden from the telecaller board.
    private static final List<String> BD_OWNED_STATUSES =
            Arrays.asList("Contacted", "In Discussion", "Proposal Sent", "Closed Won", "Closed Lost");

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Autowired private LeadsRepo                   leadsRepo;
    @Autowired private UsersRepo                   usersRepo;
    @Autowired private LeadAccessRepo              leadAccessRepo;
    @Autowired private LeadHistoryService          leadHistoryService;
    @Autowired private RoundRobinAssignmentService roundRobinService;
    @Autowired private MailService                 mailService;

    // ── Get paginated leads for telecaller ────────────────────────────────────
    /**
     * Returns leads assigned to this telecaller, filtered by all supplied criteria.
     * All filter parameters are optional — null means "no filter on that field".
     */
    public Page<TelecallerLeadView> getLeadsForTelecaller(
            Long userId, String filterStatus, Pageable pageable,
            LocalDate assignedFrom, LocalDate assignedTo,
            String searchTerm,
            String groupName, String subGroupName,
            String priority, String source) {

        List<LeadsEntity> all = leadsRepo
                .findByAssignedToAndDeletedAtIsNullOrderByCreatedAtDesc(userId);

        // ── Status filter ────────────────────────────────────────────────────
        String effectiveFilter = (filterStatus == null || filterStatus.isBlank())
                ? "NEW" : filterStatus.toUpperCase();

        if (!"ALL".equals(effectiveFilter)) {
            all = all.stream()
                     .filter(l -> matchesFilter(l, effectiveFilter))
                     .collect(Collectors.toList());
        } else {
            // Even in ALL view, hide BD-owned pipeline leads from telecaller
            all = all.stream()
                     .filter(l -> !isBdOwned(l))
                     .collect(Collectors.toList());
        }

        // ── Date filter (createdAt / Assigned On) ────────────────────────────
        if (assignedFrom != null) {
            LocalDateTime fromDt = assignedFrom.atStartOfDay();
            LocalDateTime toDt   = (assignedTo != null ? assignedTo : assignedFrom)
                                        .plusDays(1).atStartOfDay();
            all = all.stream()
                    .filter(l -> l.getCreatedAt() != null
                             && !l.getCreatedAt().isBefore(fromDt)
                             && l.getCreatedAt().isBefore(toDt))
                    .collect(Collectors.toList());
        }

        // ── Group filter ─────────────────────────────────────────────────────
        if (groupName != null && !groupName.isBlank()) {
            final String g = groupName.trim();
            all = all.stream()
                    .filter(l -> g.equalsIgnoreCase(l.getGroupName()))
                    .collect(Collectors.toList());
        }

        // ── Sub-group / Category filter ──────────────────────────────────────
        if (subGroupName != null && !subGroupName.isBlank()) {
            final String sg = subGroupName.trim();
            all = all.stream()
                    .filter(l -> sg.equalsIgnoreCase(l.getSubGroupName()))
                    .collect(Collectors.toList());
        }

        // ── Priority filter ──────────────────────────────────────────────────
        if (priority != null && !priority.isBlank()) {
            final String p = priority.trim();
            all = all.stream()
                    .filter(l -> p.equalsIgnoreCase(l.getPriority()))
                    .collect(Collectors.toList());
        }

        // ── Source filter ────────────────────────────────────────────────────
        if (source != null && !source.isBlank()) {
            final String s = source.trim();
            all = all.stream()
                    .filter(l -> s.equalsIgnoreCase(l.getSource()))
                    .collect(Collectors.toList());
        }

        // ── Search filter (name, phone, email, leadCode) ─────────────────────
        if (searchTerm != null && !searchTerm.isBlank()) {
            String q = searchTerm.trim().toLowerCase();
            all = all.stream()
                    .filter(l -> (l.getName()     != null && l.getName().toLowerCase().contains(q))
                             || (l.getPhone()    != null && l.getPhone().contains(q))
                             || (l.getEmail()    != null && l.getEmail().toLowerCase().contains(q))
                             || (l.getLeadCode() != null && l.getLeadCode().toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        }

        // ── Sort: NEW leads first, then by most recent ───────────────────────
        all.sort((a, b) -> {
            boolean aNew = isNew(a), bNew = isNew(b);
            if (aNew && !bNew) return -1;
            if (!aNew && bNew) return  1;
            if (a.getCreatedAt() != null && b.getCreatedAt() != null)
                return b.getCreatedAt().compareTo(a.getCreatedAt());
            return 0;
        });

        // ── Paginate ─────────────────────────────────────────────────────────
        int total    = all.size();
        int pageSize = pageable.getPageSize();
        int pageNum  = pageable.getPageNumber();

        int start = pageNum * pageSize;
        if (start >= total) {
            start = (total == 0) ? 0 : ((total - 1) / pageSize) * pageSize;
        }
        start = Math.max(0, start);
        int end = Math.min(start + pageSize, total);

        List<TelecallerLeadView> pageContent = all.subList(start, end)
                .stream().map(this::toView).collect(Collectors.toList());

        return new PageImpl<>(pageContent, pageable, total);
    }

    private boolean isNew(LeadsEntity l) {
        String s = l.getStatus();
        // "New" is the default; null/empty also counts as new
        return s == null || s.isBlank() || "New".equalsIgnoreCase(s);
    }

    /** Returns true when the lead's main status is in the BD+ pipeline — not visible to telecaller. */
    private boolean isBdOwned(LeadsEntity l) {
        return BD_OWNED_STATUSES.stream().anyMatch(s -> s.equalsIgnoreCase(l.getStatus()));
    }

    private boolean matchesFilter(LeadsEntity l, String filter) {
        // Never show BD-owned leads to telecaller regardless of filter
        if (isBdOwned(l)) return false;
        return switch (filter) {
            case "NEW"            -> isNew(l);
            case "INTERESTED"     -> "Interested".equalsIgnoreCase(l.getStatus());
            case "NOT_INTERESTED" -> "Not Interested".equalsIgnoreCase(l.getStatus());
            case "NOT_RESPONDED"  -> "Not Responded".equalsIgnoreCase(l.getStatus());
            case "KEEP_IN_VIEW"   -> "Keep in View".equalsIgnoreCase(l.getStatus());
            default               -> true;
        };
    }

    // ── Get single lead detail ────────────────────────────────────────────────
    public TelecallerLeadView getLeadDetailForTelecaller(Long leadId, Long userId)
            throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        if (lead.getDeletedAt() != null)
            throw new CustomException("Lead not found");

        boolean hasAccess = userId.equals(lead.getAssignedTo())
                || leadAccessRepo.existsByLeadIdAndUserId(leadId, userId);
        if (!hasAccess)
            throw new CustomException("Access denied: this lead is not assigned to you");

        return toView(lead);
    }

    // ── Update telecaller status ──────────────────────────────────────────────
    @Transactional
    public void updateTelecallerStatus(Long leadId, Long userId,
                                       TelecallerStatusUpdateRequest req)
            throws CustomException {

        String newStatus = req.getTelecallerStatus();
        if (newStatus == null || !VALID_STATUSES.contains(newStatus.toUpperCase()))
            throw new CustomException("Invalid status. Must be: INTERESTED, NOT_INTERESTED, NOT_RESPONDED, KEEP_IN_VIEW");

        newStatus = newStatus.toUpperCase();

        if ("NOT_INTERESTED".equals(newStatus)
                && (req.getReason() == null || req.getReason().isBlank()))
            throw new CustomException("Reason is required when marking Not Interested");

        if ("INTERESTED".equals(newStatus)
                && (req.getDiscussionNote() == null || req.getDiscussionNote().isBlank()))
            throw new CustomException("Discussion note is required when marking Interested");

        if ("INTERESTED".equals(newStatus)
                && (req.getTcPropertyType() == null || req.getTcPropertyType().isBlank()))
            throw new CustomException("Property type is required when marking Interested");

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        boolean hasAccess = userId.equals(lead.getAssignedTo())
                || leadAccessRepo.existsByLeadIdAndUserId(leadId, userId);
        if (!hasAccess)
            throw new CustomException("Access denied: this lead is not assigned to you");

        if ("Closed Won".equalsIgnoreCase(lead.getStatus()))
            throw new CustomException("This lead is Closed Won and cannot be updated by the telecaller.");

        // Block if lead has been progressed to BD+ pipeline stage
        if (isBdOwned(lead))
            throw new CustomException("This lead is in the " + lead.getStatus() + " stage and can only be updated by BD/Admin.");

        String oldStatus = lead.getTelecallerStatus();

        // Write the status directly to the main leads.status column (single status)
        switch (newStatus) {
            case "NEW"            -> lead.setStatus("New");
            case "INTERESTED"     -> lead.setStatus("Interested");
            case "NOT_RESPONDED"  -> lead.setStatus("Not Responded");
            case "KEEP_IN_VIEW"   -> lead.setStatus("Keep in View");
            case "NOT_INTERESTED" -> lead.setStatus("Not Interested");
        }
        // Mirror to telecaller_status for audit/history continuity
        lead.setTelecallerStatus(newStatus);
        lead.setTelecallerStatusUpdatedAt(LocalDateTime.now());

        if (req.getReason() != null)
            lead.setTelecallerReason(req.getReason().trim());

        // For KEEP_IN_VIEW, save the callback date if provided
        if ("KEEP_IN_VIEW".equals(newStatus) && req.getKivReminderDate() != null
                && !req.getKivReminderDate().isBlank()) {
            try {
                lead.setKivReminderDate(java.time.LocalDate.parse(req.getKivReminderDate()));
            } catch (Exception ex) {
                log.warn("Invalid kivReminderDate '{}' for lead {}", req.getKivReminderDate(), leadId);
            }
        }

        if ("INTERESTED".equals(newStatus)) {
            lead.setTcDiscussionNote(req.getDiscussionNote().trim());

            if (req.getTcLocation() != null && !req.getTcLocation().isBlank())
                lead.setTcLocation(req.getTcLocation().trim());
            if (req.getTcState()    != null && !req.getTcState().isBlank())
                lead.setState(req.getTcState().trim());
            if (req.getTcDistrict() != null && !req.getTcDistrict().isBlank())
                lead.setDistrict(req.getTcDistrict().trim());
            if (req.getTcCity()     != null && !req.getTcCity().isBlank())
                lead.setCity(req.getTcCity().trim());
            if (req.getTcPincode()  != null && !req.getTcPincode().isBlank())
                lead.setPincode(req.getTcPincode().trim());

            if (req.getTcSiteVisitDate() != null && !req.getTcSiteVisitDate().isBlank()) {
                try {
                    lead.setTcSiteVisitDate(LocalDate.parse(req.getTcSiteVisitDate()));
                } catch (DateTimeParseException ex) {
                    log.warn("Could not parse tcSiteVisitDate '{}' for lead {}", req.getTcSiteVisitDate(), leadId);
                }
            }

            if (req.getTcPropertyType()  != null && !req.getTcPropertyType().isBlank())
                lead.setTcPropertyType(req.getTcPropertyType().trim());
            if (req.getTcQuotedPrice()   != null && !req.getTcQuotedPrice().isBlank())
                lead.setTcQuotedPrice(req.getTcQuotedPrice().trim());
            if (req.getTcAddons()        != null && !req.getTcAddons().isBlank())
                lead.setTcAddons(req.getTcAddons().trim());
            if (req.getTcOtherComments() != null && !req.getTcOtherComments().isBlank())
                lead.setTcOtherComments(req.getTcOtherComments().trim());

            if (req.getCapacity()     != null && !req.getCapacity().isBlank())
                lead.setCapacity(req.getCapacity().trim());
            if (req.getCapacityUnit() != null && !req.getCapacityUnit().isBlank())
                lead.setCapacityUnit(req.getCapacityUnit().trim());
            else if (req.getCapacity() != null && !req.getCapacity().isBlank())
                lead.setCapacityUnit("kW");

            if (req.getTcMonthlyBill()           != null && !req.getTcMonthlyBill().isBlank())
                lead.setTcMonthlyBill(req.getTcMonthlyBill().trim());
            if (req.getTcExistingContractLoad()  != null && !req.getTcExistingContractLoad().isBlank())
                lead.setTcExistingContractLoad(req.getTcExistingContractLoad().trim());
            if (req.getTcRequiredContractLoad()  != null && !req.getTcRequiredContractLoad().isBlank())
                lead.setTcRequiredContractLoad(req.getTcRequiredContractLoad().trim());

            if (req.getSolarScheme()     != null)
                lead.setSolarScheme(req.getSolarScheme().isBlank() ? null : req.getSolarScheme().trim());
            if (req.getSubsidyRequired() != null)
                lead.setSubsidyRequired(req.getSubsidyRequired().isBlank() ? null : req.getSubsidyRequired().trim());

            Long bdUserId = roundRobinService.getNextBD(lead.getGroupName(), lead.getSubGroupName());
            if (bdUserId != null) {
                lead.setBdAssignedTo(bdUserId);
                lead.setBdAssignedAt(LocalDateTime.now());

                if (!leadAccessRepo.existsByLeadIdAndUserId(leadId, bdUserId)) {
                    LeadAccessEntity bdAccess = new LeadAccessEntity();
                    bdAccess.setLeadId(leadId);
                    bdAccess.setUserId(bdUserId);
                    bdAccess.setRole("BD");
                    leadAccessRepo.save(bdAccess);
                }

                try {
                    String bdEmail = usersRepo.findUserMailWithUserId(bdUserId);
                    String bdName  = usersRepo.findUserNameWithUserId(bdUserId);
                    String tcName  = usersRepo.findUserNameWithUserId(userId);
                    if (bdEmail != null && !bdEmail.isBlank()) {
                        String subject  = "Lead Alert: A Qualified Lead Awaits You – " + lead.getLeadCode();
                        String htmlBody = buildBDAssignmentEmailBody(bdName, tcName, lead, req);
                        mailService.sendEmail(bdEmail, subject, htmlBody);
                    }
                } catch (Exception emailEx) {
                    log.warn("BD assignment email failed for lead {} to BD {}: {}", leadId, bdUserId, emailEx.getMessage());
                }
            }

            if (!leadAccessRepo.existsByLeadIdAndUserId(leadId, userId)) {
                LeadAccessEntity tcAccess = new LeadAccessEntity();
                tcAccess.setLeadId(leadId);
                tcAccess.setUserId(userId);
                tcAccess.setRole("TELECALLER");
                leadAccessRepo.save(tcAccess);
            }
        }

        leadsRepo.save(lead);

        String description = switch (newStatus) {
            case "INTERESTED"     -> buildInterestedHistoryNote(req, lead);
            case "NOT_INTERESTED" -> "Telecaller marked as Not Interested. Reason: " + req.getReason();
            case "NOT_RESPONDED"  -> "Telecaller marked Not Responded — resurfaces in 7 days";
            case "KEEP_IN_VIEW"   -> "Telecaller marked Keep in View. Conversation: " + req.getReason()
                    + (req.getKivReminderDate() != null && !req.getKivReminderDate().isBlank()
                        ? ". Callback date: " + req.getKivReminderDate() : "");
            default               -> "Telecaller status updated to " + newStatus;
        };

        try {
            leadHistoryService.addHistory(
                leadId, "TELECALLER_STATUS_CHANGE",
                "telecallerStatus", oldStatus, newStatus,
                description, userId);
        } catch (Exception e) {
            log.warn("History save failed for lead {}: {}", leadId, e.getMessage());
        }
    }

    // ── Dashboard stats (filter-aware) ────────────────────────────────────────
    /**
     * Returns summary counts for the KPI cards.
     * Respects the same date / group / category / priority / source filters
     * as the leads list so the numbers always match the filtered view.
     */
    public TelecallerDashboardStats getDashboardStats(
            Long userId,
            LocalDate assignedFrom, LocalDate assignedTo,
            String groupName, String subGroupName,
            String priority, String source) {

        List<LeadsEntity> all = leadsRepo
                .findByAssignedToAndDeletedAtIsNullOrderByCreatedAtDesc(userId);

        // ── Apply the same filters as getLeadsForTelecaller ──────────────────

        // Date filter
        if (assignedFrom != null) {
            LocalDateTime fromDt = assignedFrom.atStartOfDay();
            LocalDateTime toDt   = (assignedTo != null ? assignedTo : assignedFrom)
                                        .plusDays(1).atStartOfDay();
            all = all.stream()
                    .filter(l -> l.getCreatedAt() != null
                             && !l.getCreatedAt().isBefore(fromDt)
                             && l.getCreatedAt().isBefore(toDt))
                    .collect(Collectors.toList());
        }

        // Group filter
        if (groupName != null && !groupName.isBlank()) {
            final String g = groupName.trim();
            all = all.stream()
                    .filter(l -> g.equalsIgnoreCase(l.getGroupName()))
                    .collect(Collectors.toList());
        }

        // Sub-group / Category filter
        if (subGroupName != null && !subGroupName.isBlank()) {
            final String sg = subGroupName.trim();
            all = all.stream()
                    .filter(l -> sg.equalsIgnoreCase(l.getSubGroupName()))
                    .collect(Collectors.toList());
        }

        // Priority filter
        if (priority != null && !priority.isBlank()) {
            final String p = priority.trim();
            all = all.stream()
                    .filter(l -> p.equalsIgnoreCase(l.getPriority()))
                    .collect(Collectors.toList());
        }

        // Source filter
        if (source != null && !source.isBlank()) {
            final String s = source.trim();
            all = all.stream()
                    .filter(l -> s.equalsIgnoreCase(l.getSource()))
                    .collect(Collectors.toList());
        }

        // ── Exclude BD-owned pipeline leads from all stats ───────────────────
        all = all.stream().filter(l -> !isBdOwned(l)).collect(Collectors.toList());

        // ── Count by status (on filtered list) ───────────────────────────────
        long total         = all.size();
        long newLeads      = all.stream()
                                .filter(this::isNew)
                                .count();
        long interested    = all.stream()
                                .filter(l -> "Interested".equalsIgnoreCase(l.getStatus()))
                                .count();
        long notInterested = all.stream()
                                .filter(l -> "Not Interested".equalsIgnoreCase(l.getStatus()))
                                .count();
        long notResponded  = all.stream()
                                .filter(l -> "Not Responded".equalsIgnoreCase(l.getStatus()))
                                .count();
        long keepInView    = all.stream()
                                .filter(l -> "Keep in View".equalsIgnoreCase(l.getStatus()))
                                .count();

        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
        long resurfaces = all.stream()
                .filter(l -> "Not Responded".equalsIgnoreCase(l.getStatus())
                          && l.getTelecallerStatusUpdatedAt() != null
                          && l.getTelecallerStatusUpdatedAt().isBefore(cutoff))
                .count();

        TelecallerDashboardStats stats = new TelecallerDashboardStats();
        stats.setTotal(total);
        stats.setPending(newLeads);
        stats.setInterested(interested);
        stats.setNotInterested(notInterested);
        stats.setNotResponded(notResponded);
        stats.setKeepInView(keepInView);
        stats.setResurfacedToday(resurfaces);
        return stats;
    }

    // ── Update editable lead fields ───────────────────────────────────────────
    @Transactional
    public TelecallerLeadView updateLeadDetails(Long leadId, Long userId,
                                                 Map<String, String> fields)
            throws CustomException {

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        if (lead.getDeletedAt() != null)
            throw new CustomException("Lead not found");

        boolean hasAccess = userId.equals(lead.getAssignedTo())
                || leadAccessRepo.existsByLeadIdAndUserId(leadId, userId);
        if (!hasAccess)
            throw new CustomException("Access denied: this lead is not assigned to you");

        if ("Closed Won".equalsIgnoreCase(lead.getStatus()))
            throw new CustomException("This lead is Closed Won and cannot be edited.");

        if (fields.containsKey("name")) {
            String name = fields.get("name") == null ? "" : fields.get("name").trim();
            if (!name.isBlank()) lead.setName(name);
        }
        if (fields.containsKey("email")) {
            String email = fields.get("email") == null ? "" : fields.get("email").trim();
            if (!email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                throw new CustomException("Invalid email format");
            lead.setEmail(email.isBlank() ? null : email.toLowerCase());
        }
        if (fields.containsKey("phone")) {
            String phone = fields.get("phone") == null ? "" : fields.get("phone").trim().replaceAll("\\s", "");
            if (!phone.isBlank() && !phone.matches("\\d{10}"))
                throw new CustomException("Phone must be exactly 10 digits");
            if (!phone.isBlank()) lead.setPhone(phone);
        }

        if (fields.containsKey("source"))        lead.setSource(nullIfBlank(fields.get("source")));
        if (fields.containsKey("priority")) {
            String p = nullIfBlank(fields.get("priority"));
            lead.setPriority(p != null ? p : "Medium");
        }
        if (fields.containsKey("enquiry"))        lead.setEnquiry(nullIfBlank(fields.get("enquiry")));
        if (fields.containsKey("state"))          lead.setState(nullIfBlank(fields.get("state")));
        if (fields.containsKey("district"))       lead.setDistrict(nullIfBlank(fields.get("district")));
        if (fields.containsKey("city"))           lead.setCity(nullIfBlank(fields.get("city")));
        if (fields.containsKey("pincode"))        lead.setPincode(nullIfBlank(fields.get("pincode")));
        if (fields.containsKey("referralName"))   lead.setReferralName(nullIfBlank(fields.get("referralName")));
        if (fields.containsKey("referralPhone"))  lead.setReferralPhone(nullIfBlank(fields.get("referralPhone")));
        if (fields.containsKey("capacity"))       lead.setCapacity(nullIfBlank(fields.get("capacity")));
        if (fields.containsKey("capacityUnit"))   lead.setCapacityUnit(
                fields.get("capacityUnit") != null && !fields.get("capacityUnit").isBlank()
                        ? fields.get("capacityUnit").trim() : "kW");
        if (fields.containsKey("groupName"))      lead.setGroupName(nullIfBlank(fields.get("groupName")));
        if (fields.containsKey("subGroupName"))   lead.setSubGroupName(nullIfBlank(fields.get("subGroupName")));
        if (fields.containsKey("solarScheme"))    lead.setSolarScheme(nullIfBlank(fields.get("solarScheme")));
        if (fields.containsKey("subsidyRequired")) lead.setSubsidyRequired(nullIfBlank(fields.get("subsidyRequired")));

        leadsRepo.save(lead);

        try {
            leadHistoryService.addHistory(leadId, "LEAD_UPDATED",
                null, null, null, "Lead details updated by telecaller", userId);
        } catch (Exception e) {
            log.warn("History write failed for lead {} update: {}", leadId, e.getMessage());
        }

        return toView(lead);
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ── Bill file BLOB storage ────────────────────────────────────────────────
    @Transactional
    public void saveBillFileToDb(Long leadId, Long userId, MultipartFile file)
            throws Exception {

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found: " + leadId));
        if (lead.getDeletedAt() != null)
            throw new CustomException("Lead not found");

        boolean hasAccess = userId.equals(lead.getAssignedTo())
                || leadAccessRepo.existsByLeadIdAndUserId(leadId, userId);
        if (!hasAccess)
            throw new CustomException("Access denied: this lead is not assigned to you");

        lead.setTcBillFileData(file.getBytes());
        lead.setTcBillFileName(file.getOriginalFilename());
        lead.setTcBillFileType(
            file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        lead.setTcHasBillFile(true);

        leadsRepo.save(lead);

        try {
            leadHistoryService.addHistory(leadId, "LEAD_UPDATED",
                null, null, null,
                "Electricity bill uploaded: " + file.getOriginalFilename(), userId);
        } catch (Exception e) {
            log.warn("History write failed for bill upload on lead {}: {}", leadId, e.getMessage());
        }
    }

    public LeadsEntity getLeadEntityForBill(Long leadId, Long userId, String userRole)
            throws CustomException {

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found: " + leadId));
        if (lead.getDeletedAt() != null)
            throw new CustomException("Lead not found");
        if (!lead.isTcHasBillFile())
            throw new CustomException("No bill file stored for this lead");
        return lead;
    }

    // ── Entity → View ─────────────────────────────────────────────────────────
    private TelecallerLeadView toView(LeadsEntity e) {
        TelecallerLeadView v = new TelecallerLeadView();
        v.setId(e.getId());
        v.setLeadCode(e.getLeadCode());
        v.setName(e.getName());
        v.setEmail(e.getEmail());
        v.setPhone(e.getPhone());
        v.setSource(e.getSource());
        v.setPriority(e.getPriority());
        v.setEnquiry(e.getEnquiry());
        v.setGroupName(e.getGroupName());
        v.setSubGroupName(e.getSubGroupName());
        v.setState(e.getState());
        v.setDistrict(e.getDistrict());
        v.setCity(e.getCity());
        v.setPincode(e.getPincode());
        v.setSolarScheme(e.getSolarScheme());
        v.setSubsidyRequired(e.getSubsidyRequired());
        v.setReferralName(e.getReferralName());
        v.setReferralPhone(e.getReferralPhone());
        v.setCapacity(e.getCapacity());
        v.setCapacityUnit(e.getCapacityUnit());
        v.setTcDiscussionNote(e.getTcDiscussionNote());
        v.setTcLocation(e.getTcLocation());
        v.setTcSiteVisitDate(e.getTcSiteVisitDate() != null
                ? e.getTcSiteVisitDate().format(DATE_FMT) : null);
        v.setTcPropertyType(e.getTcPropertyType());
        v.setTcQuotedPrice(e.getTcQuotedPrice());
        v.setTcAddons(e.getTcAddons());
        v.setTcOtherComments(e.getTcOtherComments());
        v.setTcMonthlyBill(e.getTcMonthlyBill());
        v.setTcExistingContractLoad(e.getTcExistingContractLoad());
        v.setTcRequiredContractLoad(e.getTcRequiredContractLoad());
        v.setTcBillFileName(e.getTcBillFileName());
        v.setTcBillFileType(e.getTcBillFileType());
        v.setTcHasBillFile(e.isTcHasBillFile());
        // Derive the effective telecaller status from the single main status field
        v.setTelecallerStatus(mainStatusToTcStatus(e.getStatus()));
        v.setTelecallerReason(e.getTelecallerReason());
        v.setKivReminderDate(e.getKivReminderDate() != null ? e.getKivReminderDate().toString() : null);
        v.setLeadStatus(e.getStatus());
        v.setHandedOffToBD("INTERESTED".equalsIgnoreCase(e.getTelecallerStatus()));
        v.setCreatedAt(e.getCreatedAt() != null ? e.getCreatedAt().format(FMT) : null);
        v.setTelecallerStatusUpdatedAt(
            e.getTelecallerStatusUpdatedAt() != null
                ? e.getTelecallerStatusUpdatedAt().format(FMT) : null);

        if (e.getBdAssignedTo() != null) {
            v.setBdAssignedToId(e.getBdAssignedTo());
            usersRepo.findById(e.getBdAssignedTo())
                     .ifPresent(u -> v.setBdAssignedToName(u.getName()));
            v.setBdAssignedAt(e.getBdAssignedAt() != null
                    ? e.getBdAssignedAt().format(FMT) : null);
        }

        if (e.getAssignedTo() != null) {
            usersRepo.findById(e.getAssignedTo())
                     .ifPresent(u -> v.setTelecallerName(u.getName()));
        }

        return v;
    }

    /**
     * Maps the single main lead status to a telecaller-view status key.
     * This ensures the frontend board always reflects the single source of truth.
     */
    private String mainStatusToTcStatus(String mainStatus) {
        if (mainStatus == null) return "NEW";
        return switch (mainStatus) {
            case "Interested"     -> "INTERESTED";
            case "Not Interested" -> "NOT_INTERESTED";
            case "Not Responded"  -> "NOT_RESPONDED";
            case "Keep in View"   -> "KEEP_IN_VIEW";
            // BD-owned stages — should not appear in telecaller board, but map for safety
            case "Contacted"      -> "CONTACTED";
            case "In Discussion"  -> "IN_DISCUSSION";
            case "Proposal Sent"  -> "PROPOSAL_SENT";
            case "Closed Won"     -> "CLOSED_WON";
            case "Closed Lost"    -> "CLOSED_LOST";
            default               -> "NEW";
        };
    }

    // ── BD Assignment Email ───────────────────────────────────────────────────
    private String buildBDAssignmentEmailBody(String bdName, String tcName,
                                               LeadsEntity lead,
                                               TelecallerStatusUpdateRequest req) {
        String leadCode     = lead.getLeadCode()     != null ? lead.getLeadCode()     : "N/A";
        String leadName     = lead.getName()          != null ? lead.getName()          : "N/A";
        String leadPhone    = lead.getPhone()         != null ? lead.getPhone()         : "N/A";
        String groupName    = lead.getGroupName()     != null ? lead.getGroupName()     : "N/A";
        String subGroup     = lead.getSubGroupName()  != null ? lead.getSubGroupName()  : "N/A";
        String priority     = lead.getPriority()      != null ? lead.getPriority()      : "Medium";
        String location     = req.getTcLocation() != null && !req.getTcLocation().isBlank()
                                 ? req.getTcLocation()
                                 : (lead.getCity() != null ? lead.getCity() : "N/A");
        String propertyType = req.getTcPropertyType() != null ? req.getTcPropertyType() : "N/A";
        String siteVisit    = req.getTcSiteVisitDate() != null && !req.getTcSiteVisitDate().isBlank()
                                 ? req.getTcSiteVisitDate() : "Not Scheduled";
        String quotedPrice  = req.getTcQuotedPrice() != null && !req.getTcQuotedPrice().isBlank()
                                 ? "&#8377; " + req.getTcQuotedPrice() : "N/A";
        String capacity     = (req.getCapacity() != null && !req.getCapacity().isBlank())
                                 ? req.getCapacity() + " " + (req.getCapacityUnit() != null ? req.getCapacityUnit() : "kW")
                                 : (lead.getCapacity() != null
                                     ? lead.getCapacity() + " " + (lead.getCapacityUnit() != null ? lead.getCapacityUnit() : "kW")
                                     : "N/A");
        String discussion   = req.getDiscussionNote()   != null ? req.getDiscussionNote()   : "";
        String addons       = req.getTcAddons()         != null && !req.getTcAddons().isBlank() ? req.getTcAddons() : "None";
        String otherComments= req.getTcOtherComments()  != null && !req.getTcOtherComments().isBlank() ? req.getTcOtherComments() : "None";

        return "<html><body style=\"margin:0;padding:0;background-color:#f4f6f8;font-family:Arial,sans-serif;\">" +
            "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:20px 0;\"><tr><td align=\"center\">" +
            "<table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,0.08);padding:30px;\">" +
            "<tr><td align=\"center\" style=\"padding-bottom:20px;border-bottom:3px solid #2E86C1;\">" +
            "<h2 style=\"margin:0;color:#2E86C1;\">&#128276; Lead Alert: A Qualified Lead Awaits You</h2>" +
            "<p style=\"margin:6px 0 0;color:#666;font-size:13px;\">Telecaller Conversion &mdash; Action Required</p></td></tr>" +
            "<tr><td style=\"font-size:16px;color:#333;padding:20px 0 10px;\">Hello <b>" + bdName + "</b>,</td></tr>" +
            "<tr><td style=\"font-size:15px;color:#555;padding-bottom:20px;\">A lead has been qualified as <b style=\"color:#27ae60;\">Interested</b> by Telecaller <b>" + tcName + "</b> and has been assigned to you. Please review the details below and take the next steps promptly.</td></tr>" +
            "<tr><td><table width=\"100%\" cellpadding=\"10\" cellspacing=\"0\" style=\"background:#f9fafb;border-radius:6px;font-size:14px;color:#444;border:1px solid #e0e0e0;\">" +
            "<tr style=\"background:#2E86C1;\"><td colspan=\"2\" style=\"color:#fff;font-weight:bold;padding:10px;border-radius:5px 5px 0 0;\">&#128203; Lead Information</td></tr>" +
            "<tr><td width=\"40%\"><b>Lead Code</b></td><td>" + leadCode + "</td></tr>" +
            "<tr style=\"background:#f0f4f8;\"><td><b>Lead Name</b></td><td>" + leadName + "</td></tr>" +
            "<tr><td><b>Phone</b></td><td>" + leadPhone + "</td></tr>" +
            "<tr style=\"background:#f0f4f8;\"><td><b>Group</b></td><td>" + groupName + "</td></tr>" +
            "<tr><td><b>Sub Group</b></td><td>" + subGroup + "</td></tr>" +
            "<tr style=\"background:#f0f4f8;\"><td><b>Priority</b></td><td style=\"color:#d35400;\"><b>" + priority + "</b></td></tr>" +
            "<tr><td><b>Location</b></td><td>" + location + "</td></tr>" +
            "<tr style=\"background:#f0f4f8;\"><td><b>Property Type</b></td><td>" + propertyType + "</td></tr>" +
            "<tr><td><b>Capacity</b></td><td>" + capacity + "</td></tr>" +
            "<tr style=\"background:#f0f4f8;\"><td><b>Quoted Price</b></td><td style=\"color:#27ae60;\"><b>" + quotedPrice + "</b></td></tr>" +
            "<tr><td><b>Site Visit Date</b></td><td>" + siteVisit + "</td></tr>" +
            "<tr style=\"background:#f0f4f8;\"><td><b>Add-ons</b></td><td>" + addons + "</td></tr>" +
            "</table></td></tr>" +
            "<tr><td style=\"padding-top:20px;font-size:14px;color:#555;\"><b>&#128172; Discussion Note:</b>" +
            "<div style=\"margin-top:8px;padding:12px;background:#f1f8ff;border-left:4px solid #2E86C1;border-radius:4px;font-size:14px;color:#333;\">" + discussion + "</div></td></tr>" +
            "<tr><td style=\"padding-top:16px;font-size:14px;color:#555;\"><b>&#128221; Other Comments:</b>" +
            "<div style=\"margin-top:8px;padding:10px;background:#f1f1f1;border-radius:5px;font-size:14px;color:#333;\">" + otherComments + "</div></td></tr>" +
            "<tr><td align=\"center\" style=\"padding-top:28px;\">" +
            "<a href=\"https://crm.sesolaenergy.com/\" style=\"background-color:#2E86C1;color:#ffffff;text-decoration:none;padding:12px 30px;border-radius:6px;font-size:15px;font-weight:bold;display:inline-block;\">&#128279; Open CRM Portal</a></td></tr>" +
            "<tr><td style=\"height:24px;\"></td></tr>" +
            "<tr><td style=\"padding-top:28px;font-size:12px;color:#aaa;text-align:center;border-top:1px solid #eee;\">Regards,<br><b>SESOLA CRM System</b></td></tr>" +
            "</table></td></tr></table></body></html>";
    }

    private String buildInterestedHistoryNote(TelecallerStatusUpdateRequest req, LeadsEntity lead) {
        StringBuilder sb = new StringBuilder("Telecaller marked Interested.");
        sb.append(" Discussion: ").append(req.getDiscussionNote());
        if (req.getTcPropertyType() != null && !req.getTcPropertyType().isBlank())
            sb.append(" | Property: ").append(req.getTcPropertyType());
        if (req.getTcLocation() != null && !req.getTcLocation().isBlank())
            sb.append(" | Location: ").append(req.getTcLocation());
        if (req.getTcSiteVisitDate() != null && !req.getTcSiteVisitDate().isBlank())
            sb.append(" | Site visit: ").append(req.getTcSiteVisitDate());
        if (req.getTcQuotedPrice() != null && !req.getTcQuotedPrice().isBlank())
            sb.append(" | Quoted: ₹").append(req.getTcQuotedPrice());
        if (req.getTcAddons() != null && !req.getTcAddons().isBlank())
            sb.append(" | Add-ons: ").append(req.getTcAddons());
        if (lead.getBdAssignedTo() != null)
            sb.append(" | Assigned to BD user #").append(lead.getBdAssignedTo());
        else
            sb.append(" | No BD user available");
        return sb.toString();
    }
}