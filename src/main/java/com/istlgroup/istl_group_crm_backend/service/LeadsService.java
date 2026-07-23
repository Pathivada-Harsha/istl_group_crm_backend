package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.CustomerWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadFilterRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadRequestWrapper;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.TeamEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.constants.LeadStatusRank;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Module;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Type;

@Service
public class LeadsService {

    // ── Lead funnel ranking lives in constants/LeadStatusRank — shared with
    //    TelecallerLeadService and mirrored by src/constants/leadStatus.js on the
    //    frontend. See that class for why "Keep in View" / "Closed Lost" are
    //    deliberately unranked and why "Not Interested" is ranked.

    @Autowired
    private LeadsRepo leadsRepo;

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private CustomersService customersService;

    @Autowired
    private FollowupsService followupsService;

    @Autowired
    private LeadHistoryService leadHistoryService;

    @Autowired
    private ProposalsService updatedProposal;

    @Autowired
    private RoundRobinAssignmentService roundRobinService;

    @Autowired
    private LeadAccessRepo leadAccessRepo;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MailService mailService;

    // NEW — used to resolve level_order dynamically without hardcoding role names
    @Autowired
    private RoleHierarchyService roleHierarchyService;

    // NEW — used to fetch all team member IDs for an L3 user
    @Autowired
    private TeamRepository teamRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─────────────────────────────────────────────────────────────────────────
    // VISIBILITY HELPER
    //
    // Resolves which set of leads a user can see based on their level_order
    // in the role_hierarchy table — no role names are hardcoded here.
    //
    //   level <= 2  →  L1/L2 (SUPERADMIN, ADMIN)          → all leads
    //   level == 3  →  L3 (MANAGER, BD_MANAGER, etc.)      → team-scoped leads
    //   level >= 4  →  L4+ (BD_EXEC, SALES_EXEC, etc.)     → own leads only
    //
    // If tomorrow a new role is added at L3 in role_hierarchy, it automatically
    // gets team-scoped visibility with zero code changes here.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the list of team member IDs for an L3 user.
     * If the user belongs to no team, returns a list containing only their own ID
     * so they still see their own leads as a fallback.
     */
    private List<Long> resolveTeamMemberIds(Long userId) {
        List<Long> memberIds = teamRepository.findTeamMemberIdsByUserId(userId);
        if (memberIds == null || memberIds.isEmpty()) {
            // L3 user not assigned to any team — fall back to own leads
            return Collections.singletonList(userId);
        }
        return memberIds;
    }

    /**
     * True when the given user belongs to at least one team whose name ends with
     * "_AffiliateTeam" (case-insensitive). Leads created by such users are never
     * auto-routed to a telecaller or a BD — they stay with a lead handler picked
     * at creation, or with the creator. See createLead / updateLead.
     */
    public boolean isAffiliateTeamCreator(Long userId) {
        return affiliateTeamNameOf(userId) != null;
    }

    /**
     * Returns the matched affiliate team name for the user, or null.
     * Checks BOTH sources of team membership so it works regardless of how the
     * user was put on the team:
     *   1. the team_members junction (Teams screen), and
     *   2. the denormalized users.team column (set directly on the user profile).
     */
    private String affiliateTeamNameOf(Long userId) {
        if (userId == null) return null;
        // 1. Teams via the team_members junction
        for (TeamEntity t : teamRepository.findByMemberId(userId)) {
            if (isAffiliateTeamName(t.getName())) return t.getName();
        }
        // 2. Denormalized users.team column
        String team = usersRepo.findById(userId).map(u -> u.getTeam()).orElse(null);
        if (isAffiliateTeamName(team)) return team;
        return null;
    }

    /**
     * A team name is an affiliate team when, ignoring case and any separators
     * (spaces, underscores, hyphens), it ends with "affiliateteam". This matches
     * "XYZ_AffiliateTeam", "XYZ Affiliate Team", "xyz-affiliate-team", etc.
     */
    private static boolean isAffiliateTeamName(String name) {
        if (name == null) return false;
        String norm = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        return norm.endsWith("affiliateteam");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXISTING METHOD — kept for backwards compatibility
    // (used by /by-group-subgroup, /group/{name}, /status/{status}, etc.)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get all leads based on user role (non-paginated, kept for other endpoints).
     */
    public List<LeadWrapper> getAllLeads(Long userId, String userRole, String groupName, String subGroupName) {
        List<LeadsEntity> leads;

        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2) {
            // L1 / L2 — see everything
            leads = leadsRepo.findByDeletedAtIsNullOrderByCreatedAtDesc();

        } else if (level == 3) {
            // L3 — team-scoped: all leads created or assigned to any team member
            List<Long> memberIds = resolveTeamMemberIds(userId);
            leads = leadsRepo.findByTeamMemberLeads(memberIds);

        } else {
            // L4 and below — own leads only
            leads = leadsRepo.findByCreatedByOrAssignedTo(userId);
        }

        // Apply optional in-memory group/subgroup filters (kept for backwards compat
        // with callers that pass these as query params instead of using /filter)
        if (groupName != null && !groupName.isEmpty()) {
            leads = leads.stream()
                    .filter(lead -> groupName.equals(lead.getGroupName()))
                    .collect(Collectors.toList());
        }

        if (subGroupName != null && !subGroupName.isEmpty()) {
            leads = leads.stream()
                    .filter(lead -> subGroupName.equals(lead.getSubGroupName()))
                    .collect(Collectors.toList());
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Get leads with filters based on user role (non-paginated, kept for backwards compat).
     */
    public List<LeadWrapper> getFilteredLeads(Long userId, String userRole, LeadFilterRequestWrapper filterRequest) {
        List<LeadsEntity> leads;

        LocalDateTime fromDate = null;
        LocalDateTime toDate = null;

        if (filterRequest.getFromDate() != null && !filterRequest.getFromDate().isEmpty()) {
            fromDate = LocalDateTime.parse(filterRequest.getFromDate() + " 00:00:00", DATE_FORMATTER);
        }

        if (filterRequest.getToDate() != null && !filterRequest.getToDate().isEmpty()) {
            toDate = LocalDateTime.parse(filterRequest.getToDate() + " 23:59:59", DATE_FORMATTER);
        }

        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2) {
            // L1 / L2 — all leads
            leads = leadsRepo.searchLeads(
                filterRequest.getSearchTerm(),
                filterRequest.getStatus(),
                filterRequest.getPriority(),
                filterRequest.getSource(),
                filterRequest.getGroupName(),
                filterRequest.getSubGroupName(),
                filterRequest.getAssignedTo(),
                fromDate,
                toDate
            );

        } else if (level == 3) {
            // L3 — team-scoped (non-paginated path; most callers use the paged version)
            List<Long> memberIds = resolveTeamMemberIds(userId);
            leads = leadsRepo.findByTeamMemberLeads(memberIds);

        } else {
            // L4 and below — own leads only
            leads = leadsRepo.searchLeadsForUser(
                userId,
                filterRequest.getSearchTerm(),
                filterRequest.getStatus(),
                filterRequest.getPriority(),
                filterRequest.getSource(),
                filterRequest.getGroupName(),
                filterRequest.getSubGroupName(),
                filterRequest.getAssignedTo(),
                fromDate,
                toDate
            );
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server-side paginated versions used by the updated controller
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get all leads with server-side pagination.
     * Used by GET /leads/getAll?page=N&size=N
     */
    public Page<LeadWrapper> getLeadsPaged(
            Long userId, String userRole,
            String groupName, String subGroupName,
            Pageable pageable) {

        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2) {
            // L1 / L2 — all leads
            return leadsRepo.searchLeadsPaged(
                null, null, null, null,
                blankToNull(groupName),
                blankToNull(subGroupName),
                null, null, null,
                pageable
            ).map(this::convertToWrapper);

        } else if (level == 3) {
            // L3 — team-scoped leads
            List<Long> memberIds = resolveTeamMemberIds(userId);
            return leadsRepo.searchLeadsForTeamPaged(
                memberIds,
                null, null, null, null,
                blankToNull(groupName),
                blankToNull(subGroupName),
                null, null, null,
                pageable
            ).map(this::convertToWrapper);

        } else {
            // L4 and below — own leads only
            return leadsRepo.searchLeadsForUserPaged(
                userId,
                null, null, null, null,
                blankToNull(groupName),
                blankToNull(subGroupName),
                null, null, null,
                pageable
            ).map(this::convertToWrapper);
        }
    }

    /**
     * Get filtered leads with server-side pagination.
     * Used by POST /leads/filter?page=N&size=N
     */
    public Page<LeadWrapper> getFilteredLeadsPaged(
            Long userId, String userRole,
            LeadFilterRequestWrapper filterRequest,
            Pageable pageable) {

        LocalDateTime fromDate = null;
        LocalDateTime toDate   = null;

        if (filterRequest.getFromDate() != null && !filterRequest.getFromDate().isEmpty())
            fromDate = LocalDateTime.parse(filterRequest.getFromDate() + " 00:00:00", DATE_FORMATTER);
        if (filterRequest.getToDate() != null && !filterRequest.getToDate().isEmpty())
            toDate = LocalDateTime.parse(filterRequest.getToDate() + " 23:59:59", DATE_FORMATTER);

        String search    = blankToNull(filterRequest.getSearchTerm());
        String status    = blankToNull(filterRequest.getStatus());
        String priority  = blankToNull(filterRequest.getPriority());
        String source    = blankToNull(filterRequest.getSource());
        String groupName = blankToNull(filterRequest.getGroupName());
        String subGroup  = blankToNull(filterRequest.getSubGroupName());

        int level = roleHierarchyService.getLevelOrder(userRole);

        Page<LeadWrapper> result;
        if (level <= 2) {
            result = leadsRepo.searchLeadsPaged(
                search, status, priority, source,
                groupName, subGroup,
                filterRequest.getAssignedTo(),
                fromDate, toDate,
                pageable
            ).map(this::convertToWrapper);

        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            result = leadsRepo.searchLeadsForTeamPaged(
                memberIds,
                search, status, priority, source,
                groupName, subGroup,
                filterRequest.getAssignedTo(),
                fromDate, toDate,
                pageable
            ).map(this::convertToWrapper);

        } else {
            result = leadsRepo.searchAccessibleByUserPaged(
                userId,
                search, status, priority, source,
                groupName, subGroup,
                filterRequest.getAssignedTo(),
                fromDate, toDate,
                pageable
            ).map(this::convertToWrapper);
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXISTING METHODS below — all unchanged except hasAccessToLead
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get lead by ID with role-based access control.
     */
    public LeadWrapper getLeadById(Long leadId, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));

        if (lead.getDeletedAt() != null) {
            throw new CustomException("Lead has been deleted");
        }

        if (!hasAccessToLead(lead, userId, userRole)) {
            throw new CustomException("Access denied to this lead");
        }

        return convertToWrapper(lead);
    }

    /**
     * Create a new lead (sends assignment email to telecaller).
     */
    public LeadWrapper createLead(LeadRequestWrapper requestWrapper, Long createdBy)
            throws CustomException {
        return createLead(requestWrapper, createdBy, false);
    }

    /**
     * Create a new lead with optional email suppression.
     * Pass suppressEmail=true when called from bulk-import so one batch email
     * is sent after all rows are processed instead of one email per lead.
     */
    public LeadWrapper createLead(LeadRequestWrapper requestWrapper, Long createdBy,
                                  boolean suppressEmail)
            throws CustomException {

        LeadsEntity lead = new LeadsEntity();
        // leadCode intentionally NOT set here.
        // It is assigned after the first save using the DB auto-increment id:
        //   LEAD-{YEAR}-{id}
        // Using COUNT(*) was broken because deleted leads leave gaps,
        // causing the count to collide with still-existing lead codes.
        lead.setCustomerId(requestWrapper.getCustomerId());
        lead.setName(requestWrapper.getName());
        lead.setEmail(requestWrapper.getEmail());
        lead.setPhone(requestWrapper.getPhone());
        lead.setSource(requestWrapper.getSource());
        lead.setPriority(requestWrapper.getPriority());
        lead.setStatus(requestWrapper.getStatus() != null ? requestWrapper.getStatus() : "New");
        lead.setEnquiry(requestWrapper.getEnquiry());
        lead.setGroupName(requestWrapper.getGroupName());
        lead.setSubGroupName(requestWrapper.getSubGroupName());
        lead.setState(requestWrapper.getState());
        lead.setDistrict(requestWrapper.getDistrict());
        lead.setCity(requestWrapper.getCity());
        lead.setPincode(requestWrapper.getPincode());
        lead.setSolarScheme(requestWrapper.getSolarScheme());
        lead.setReferralName(requestWrapper.getReferralName());
        lead.setReferralPhone(requestWrapper.getReferralPhone());
        lead.setCapacity(requestWrapper.getCapacity());
        lead.setCapacityUnit(requestWrapper.getCapacityUnit() != null ? requestWrapper.getCapacityUnit() : "kW");

        // Lead Owner — use provided value, or fall back to the creator's name
        if (requestWrapper.getLeadOwner() != null && !requestWrapper.getLeadOwner().trim().isEmpty()) {
            lead.setLeadOwner(requestWrapper.getLeadOwner().trim());
        } else if (createdBy != null) {
            usersRepo.findById(createdBy).ifPresent(u ->
                lead.setLeadOwner(u.getName() != null ? u.getName() : u.getUser_id()));
        }

        lead.setCreatedBy(createdBy);

        // ── Assignment logic ───────────────────────────────────────────────────
        Long assignedTo = requestWrapper.getAssignedTo();

        // Affiliate-team leads are never auto-routed to a telecaller or a BD.
        String affiliateTeam = affiliateTeamNameOf(createdBy);
        boolean affiliateCreator = affiliateTeam != null;
        String rawTeam = usersRepo.findById(createdBy).map(u -> u.getTeam()).orElse(null);
        System.out.println("[LeadsService.createLead] createdBy=" + createdBy
                + " affiliateCreator=" + affiliateCreator
                + " matchedTeam=" + affiliateTeam
                + " users.team=" + rawTeam);

        boolean isClosedWon = "Closed Won".equalsIgnoreCase(requestWrapper.getStatus());
        boolean isReferral   = "Referral".equalsIgnoreCase(requestWrapper.getSource());
        // Leads created at BD+ pipeline stages don't need a telecaller
        boolean isBdOwnedStatus = requestWrapper.getStatus() != null &&
                java.util.Arrays.asList("Interested", "Contacted", "In Discussion",
                        "Proposal Sent", "Closed Won", "Closed Lost")
                        .contains(requestWrapper.getStatus());

        if (affiliateCreator) {
            // Affiliate-team lead: honour a "lead handler" explicitly picked at
            // creation; otherwise the lead simply stays with its creator. No
            // telecaller round-robin, no BD — a manager can reassign later.
            lead.setAssignedTo(assignedTo != null ? assignedTo : createdBy);
            lead.setBdAssignedTo(null);

        } else if (assignedTo != null && !isClosedWon && !isReferral) {
            // Explicit assignment selected in the form.
            // Branch on the ASSIGNEE's role so that a direct assignment to a
            // BD or a senior user is NOT mistaken for a telecaller assignment.
            String assigneeRole = usersRepo.findById(assignedTo)
                    .map(u -> u.getRole())
                    .orElse("");

            if ("TELECALLER".equalsIgnoreCase(assigneeRole)) {
                // Direct assignment to a telecaller → normal telecaller slot.
                lead.setAssignedTo(assignedTo);

            } else if (assigneeRole != null
                    && assigneeRole.toUpperCase().contains("BD")) {
                // Direct assignment to a BD user → put them in the BD slot,
                // leave the telecaller slot empty (no telecaller, no TC email),
                // and mark so the later Interested→BD round-robin is skipped.
                lead.setAssignedTo(null);
                lead.setBdAssignedTo(assignedTo);
                lead.setBdAssignedAt(java.time.LocalDateTime.now());

            } else {
                // Direct assignment to a senior user (MANAGER / ADMIN / Sales …)
                // → senior owner. Keep them in assignedTo as the owner, but DO
                // NOT trigger the telecaller round-robin or the telecaller email.
                // bdAssignedTo stays null; the Interested→BD round-robin is
                // skipped because a senior owner already exists.
                lead.setAssignedTo(assignedTo);
            }

        } else if (isClosedWon) {
            // Closed Won on creation → no telecaller needed, leave assignedTo null
            lead.setAssignedTo(null);

        } else if (isReferral) {
            // Referral leads → skip telecaller, auto-assign BD via round-robin
            try {
                Long nextBD = roundRobinService.getNextBD(
                        requestWrapper.getGroupName(), requestWrapper.getSubGroupName());
                if (nextBD != null) {
                    lead.setBdAssignedTo(nextBD);
                    lead.setBdAssignedAt(java.time.LocalDateTime.now());
                }
            } catch (Exception e) {
                System.err.println("Failed to auto-assign BD for referral lead: " + e.getMessage());
            }
            lead.setAssignedTo(null); // no telecaller for referral leads

        } else if (isBdOwnedStatus) {
            // Lead created at Interested or above — no telecaller assignment needed
            lead.setAssignedTo(null);

        } else {
            // No explicit assignment — round-robin among telecallers
            String creatorRole = usersRepo.findById(createdBy)
                    .map(u -> u.getRole())
                    .orElse("");

            if ("TELECALLER".equalsIgnoreCase(creatorRole)) {
                lead.setAssignedTo(createdBy);
            } else {
                Long nextTelecaller = roundRobinService.getNextTelecaller(
                        requestWrapper.getGroupName(),
                        requestWrapper.getSubGroupName());
                lead.setAssignedTo(nextTelecaller);
            }
        }
        // ── End assignment logic ──────────────────────────────────────────────

        // First save — gets the auto-incremented id from DB
        LeadsEntity savedLead = leadsRepo.save(lead);

        // Now assign lead code using the guaranteed-unique DB id
        savedLead.setLeadCode(String.format("LEAD-%s-%06d",
                LocalDateTime.now().getYear(), savedLead.getId()));
        savedLead = leadsRepo.save(savedLead);

        // History — creation
        try {
            String description = "Lead created";
            if (requestWrapper.getSource() != null)
                description += " from " + requestWrapper.getSource();

            leadHistoryService.addHistory(savedLead.getId(), "CREATED",
                    null, null, null, description, createdBy);

            if (savedLead.getAssignedTo() != null) {
                String assignedToName = usersRepo.findById(savedLead.getAssignedTo())
                        .map(u -> u.getName()).orElse("Unknown");

                boolean wasRoundRobin = (requestWrapper.getAssignedTo() == null);
                String  historyDesc   = wasRoundRobin
                        ? "Lead auto-assigned via round-robin to " + assignedToName
                        : "Lead assigned to " + assignedToName;

                leadHistoryService.addHistory(savedLead.getId(), "ASSIGNED",
                        "assignedTo", "Unassigned", assignedToName,
                        historyDesc, createdBy);
            }
        } catch (Exception e) {
            System.err.println("Failed to add creation history: " + e.getMessage());
        }

        // ── Closed Won on creation → auto-convert to customer ────────────────
        if ("Closed Won".equalsIgnoreCase(savedLead.getStatus()) &&
                savedLead.getCustomerId() == null) {
            try {
                CustomerWrapper customer = customersService.convertLeadToCustomer(savedLead);
                savedLead.setCustomerId(customer.getId());
                savedLead = leadsRepo.save(savedLead);
                leadHistoryService.addHistory(savedLead.getId(), "CONVERTED",
                        null, null, customer.getCustomerCode(),
                        "Lead created with Closed Won status — converted to customer: "
                                + customer.getCustomerCode(), createdBy);
            } catch (Exception e) {
                System.err.println("Failed to convert new lead to customer: " + e.getMessage());
            }
        }

        // ── Referral: create BD LeadAccess after save (need real leadId) ─────
        if (isReferral && savedLead.getBdAssignedTo() != null) {
            try {
                Long bdUserId = savedLead.getBdAssignedTo();
                if (!leadAccessRepo.existsByLeadIdAndUserId(savedLead.getId(), bdUserId)) {
                    com.istlgroup.istl_group_crm_backend.entity.LeadAccessEntity bdAccess =
                            new com.istlgroup.istl_group_crm_backend.entity.LeadAccessEntity();
                    bdAccess.setLeadId(savedLead.getId());
                    bdAccess.setUserId(bdUserId);
                    bdAccess.setRole("BD");
                    leadAccessRepo.save(bdAccess);
                }
                String bdName = usersRepo.findById(bdUserId)
                        .map(u -> u.getName()).orElse("Unknown");
                leadHistoryService.addHistory(savedLead.getId(), "ASSIGNED",
                        "bdAssignedTo", "Unassigned", bdName,
                        "Referral lead — BD auto-assigned via round-robin to " + bdName, createdBy);
            } catch (Exception e) {
                System.err.println("Failed to create BD LeadAccess for referral lead: " + e.getMessage());
            }
        }

        // ── Send assignment email to telecaller ──────────────────────────────
        // finalSavedLead is a snapshot after all re-assignments; required because
        // lambdas can only capture effectively-final variables.
        final LeadsEntity finalSavedLead = savedLead;
        if (!suppressEmail && !affiliateCreator && finalSavedLead.getAssignedTo() != null) {
            try {
                usersRepo.findById(finalSavedLead.getAssignedTo()).ifPresent(telecaller -> {
                    // Only send the "New Lead Assigned" telecaller email when the
                    // assignee is actually a TELECALLER. Senior owners directly
                    // assigned at creation must NOT receive the telecaller email.
                    boolean isTelecaller = "TELECALLER".equalsIgnoreCase(telecaller.getRole());
                    if (isTelecaller
                            && telecaller.getEmail() != null && !telecaller.getEmail().isBlank()) {
                        String subject = "New Lead Assigned to You – " + finalSavedLead.getLeadCode();
                        String body = buildSingleLeadEmailBody(telecaller.getName(), finalSavedLead);
                        mailService.sendEmail(telecaller.getEmail(), subject, body);
                    }
                });
            } catch (Exception e) {
                System.err.println("Failed to send assignment email: " + e.getMessage());
            }
        }

        // ── NOTIFICATIONS (independent of email) ──────────────────────────────
        try {
            Long assignee = finalSavedLead.getAssignedTo();
            if (assignee != null && !assignee.equals(createdBy)) {
                notificationService.createNotification(
                    assignee,
                    "New lead assigned",
                    "Lead " + finalSavedLead.getLeadCode() + " (" + finalSavedLead.getName() + ") was assigned to you.",
                    Module.LEAD, finalSavedLead.getId(), Type.LEAD_ASSIGNED);

                // "Assigned to Sales Executive" variant when the assignee is a sales role
                usersRepo.findById(assignee).ifPresent(u -> {
                    if (u.getRole() != null && u.getRole().toUpperCase().contains("SALES")) {
                        notificationService.createNotification(
                            assignee,
                            "Sales lead assigned",
                            "Sales lead " + finalSavedLead.getLeadCode() + " is now assigned to you.",
                            Module.LEAD, finalSavedLead.getId(), Type.LEAD_ASSIGNED_SALES_EXEC);
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[LeadsService] notification error on create: " + e.getMessage());
        }

        return convertToWrapper(finalSavedLead);
    }

    /**
     * Change or remove the BD executive assigned to a lead. Works on ANY lead,
     * regardless of how the BD got there (auto round-robin or manual).
     *
     *   newBdId == null  → REMOVE the current BD (clears bdAssignedTo/at + access).
     *   newBdId != null  → assign/change to that user (must be an active BD).
     *
     * Writes lead history (BD_REMOVED / BD_REASSIGNED) and, on assign, notifies
     * the new BD in-app and by email (best effort).
     */
    public LeadWrapper reassignBd(Long leadId, Long newBdId, Long actingUserId, String actingRole)
            throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));
        if (lead.getDeletedAt() != null) {
            throw new CustomException("Cannot modify a deleted lead");
        }
        if (!hasAccessToLead(lead, actingUserId, actingRole)) {
            throw new CustomException("Access denied to change the BD for this lead");
        }

        Long oldBdId = lead.getBdAssignedTo();

        // ── Remove the current BD ────────────────────────────────────────────
        if (newBdId == null) {
            if (oldBdId == null) {
                return convertToWrapper(lead); // nothing to remove
            }
            String oldBdName = usersRepo.findById(oldBdId).map(u -> u.getName()).orElse("Unknown");
            lead.setBdAssignedTo(null);
            lead.setBdAssignedAt(null);
            leadsRepo.save(lead);

            // Drop any BD access rows for this lead
            leadAccessRepo.deleteAll(leadAccessRepo.findByLeadIdAndRole(leadId, "BD"));

            try {
                leadHistoryService.addHistory(leadId, "BD_REMOVED", "bdAssignedTo",
                        oldBdName, "Unassigned",
                        "BD " + oldBdName + " removed from the lead", actingUserId);
            } catch (Exception e) {
                System.err.println("Failed to add BD_REMOVED history: " + e.getMessage());
            }
            return convertToWrapper(lead);
        }

        // ── Assign / change the BD ───────────────────────────────────────────
        if (!usersRepo.findActiveBDIds().contains(newBdId)) {
            throw new CustomException("Selected user is not an active BD executive");
        }
        if (newBdId.equals(oldBdId)) {
            return convertToWrapper(lead); // no change
        }

        String newBdName = usersRepo.findById(newBdId).map(u -> u.getName()).orElse("Unknown");
        String oldBdName = oldBdId != null
                ? usersRepo.findById(oldBdId).map(u -> u.getName()).orElse("Unknown")
                : "Unassigned";

        lead.setBdAssignedTo(newBdId);
        lead.setBdAssignedAt(LocalDateTime.now());
        leadsRepo.save(lead);

        // Refresh BD access rows: remove previous BD row(s), add the new one
        leadAccessRepo.deleteAll(leadAccessRepo.findByLeadIdAndRole(leadId, "BD"));
        if (!leadAccessRepo.existsByLeadIdAndUserId(leadId, newBdId)) {
            com.istlgroup.istl_group_crm_backend.entity.LeadAccessEntity bdAccess =
                    new com.istlgroup.istl_group_crm_backend.entity.LeadAccessEntity();
            bdAccess.setLeadId(leadId);
            bdAccess.setUserId(newBdId);
            bdAccess.setRole("BD");
            leadAccessRepo.save(bdAccess);
        }

        try {
            leadHistoryService.addHistory(leadId, "BD_REASSIGNED", "bdAssignedTo",
                    oldBdName, newBdName,
                    "BD changed from " + oldBdName + " to " + newBdName, actingUserId);
        } catch (Exception e) {
            System.err.println("Failed to add BD_REASSIGNED history: " + e.getMessage());
        }

        // Notify the new BD (in-app) + best-effort email
        try {
            if (!newBdId.equals(actingUserId)) {
                notificationService.createNotification(newBdId,
                        "Lead assigned to you",
                        "Lead " + lead.getLeadCode() + " (" + lead.getName()
                                + ") has been assigned to you as BD.",
                        Module.LEAD, lead.getId(), Type.LEAD_ASSIGNED);
            }
        } catch (Exception e) {
            System.err.println("[LeadsService] BD reassign notification error: " + e.getMessage());
        }
        try {
            String bdEmail = usersRepo.findUserMailWithUserId(newBdId);
            if (bdEmail != null && !bdEmail.isBlank()) {
                String subject = "Lead Assigned to You – " + lead.getLeadCode();
                String body = buildSingleLeadEmailBody(newBdName, lead);
                mailService.sendEmail(bdEmail, subject, body);
            }
        } catch (Exception e) {
            System.err.println("Failed to send BD reassignment email: " + e.getMessage());
        }

        return convertToWrapper(lead);
    }

    public void addProposalHistory(Long leadId, String proposalNo, Long createdBy) {
        try {
            String description = "Proposal created: " + proposalNo;
            leadHistoryService.addHistory(
                leadId,
                "PROPOSAL_CREATED",
                null, null,
                proposalNo,
                description,
                createdBy
            );
        } catch (Exception e) {
            System.err.println("Failed to add proposal history: " + e.getMessage());
        }
    }

    /**
     * Update an existing lead.
     */
    public LeadWrapper updateLead(Long leadId, LeadRequestWrapper requestWrapper, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));

        if (lead.getDeletedAt() != null) {
            throw new CustomException("Cannot update deleted lead");
        }

        if (!hasAccessToLead(lead, userId, userRole)) {
            throw new CustomException("Access denied to update this lead");
        }

        if ("Not Interested".equalsIgnoreCase(requestWrapper.getStatus())
                && (requestWrapper.getNotInterestedReason() == null
                    || requestWrapper.getNotInterestedReason().trim().isEmpty())) {
            throw new CustomException("Please provide a reason for marking this lead as Not Interested");
        }

        String oldStatus    = lead.getStatus();
        Long   oldAssignedTo = lead.getAssignedTo();
        String oldPriority  = lead.getPriority();
        String oldSource    = lead.getSource();

        // ── Downgrade guard — moving a lead BACKWARD in the funnel is allowed, but
        //    only with a reason. Applies to every user (no admin bypass). Negative
        //    exits ("Not Interested" / "Closed Lost") are unranked, so they never
        //    reach here and keep their own reason requirements above. ─────────────
        boolean isStatusDowngrade =
                requestWrapper.getStatus() != null
                && !requestWrapper.getStatus().equalsIgnoreCase(oldStatus)
                && LeadStatusRank.isDowngrade(oldStatus, requestWrapper.getStatus());

        if (isStatusDowngrade
                && (requestWrapper.getStatusDowngradeReason() == null
                    || requestWrapper.getStatusDowngradeReason().trim().isEmpty())) {
            throw new CustomException(
                "Moving this lead back from \"" + oldStatus + "\" to \"" + requestWrapper.getStatus()
                + "\" requires a reason. Please provide one.");
        }

        if (requestWrapper.getCustomerId()   != null) lead.setCustomerId(requestWrapper.getCustomerId());
        if (requestWrapper.getName()         != null) lead.setName(requestWrapper.getName());
        if (requestWrapper.getEmail()        != null) lead.setEmail(requestWrapper.getEmail());
        if (requestWrapper.getPhone()        != null) lead.setPhone(requestWrapper.getPhone());
        if (requestWrapper.getSource()       != null) lead.setSource(requestWrapper.getSource());
        if (requestWrapper.getPriority()     != null) lead.setPriority(requestWrapper.getPriority());
        if (requestWrapper.getStatus()       != null) lead.setStatus(requestWrapper.getStatus());
        if (requestWrapper.getAssignedTo()   != null) lead.setAssignedTo(requestWrapper.getAssignedTo());
        if (requestWrapper.getEnquiry()      != null) lead.setEnquiry(requestWrapper.getEnquiry());
        if (requestWrapper.getGroupName()    != null) lead.setGroupName(requestWrapper.getGroupName());
        if (requestWrapper.getSubGroupName() != null) lead.setSubGroupName(requestWrapper.getSubGroupName());
        if (requestWrapper.getState()       != null) lead.setState(requestWrapper.getState());
        if (requestWrapper.getDistrict()    != null) lead.setDistrict(requestWrapper.getDistrict());
        if (requestWrapper.getCity()        != null) lead.setCity(requestWrapper.getCity());
        if (requestWrapper.getPincode()     != null) lead.setPincode(requestWrapper.getPincode());
        if (requestWrapper.getSolarScheme() != null) lead.setSolarScheme(requestWrapper.getSolarScheme());
        if (requestWrapper.getReferralName()  != null) lead.setReferralName(requestWrapper.getReferralName());
        if (requestWrapper.getReferralPhone() != null) lead.setReferralPhone(requestWrapper.getReferralPhone());
        if (requestWrapper.getCapacity()      != null) lead.setCapacity(requestWrapper.getCapacity());
        if (requestWrapper.getCapacityUnit()  != null) lead.setCapacityUnit(requestWrapper.getCapacityUnit());

        // Lead Owner
        if (requestWrapper.getLeadOwner() != null)
            lead.setLeadOwner(requestWrapper.getLeadOwner().trim().isEmpty() ? null : requestWrapper.getLeadOwner().trim());

        // ── Closed Won attribution ────────────────────────────────────────────
        if ("Closed Won".equalsIgnoreCase(requestWrapper.getStatus())) {
            if (requestWrapper.getClosedByUserId() != null) {
                lead.setClosedByUserId(requestWrapper.getClosedByUserId());
                lead.setClosedByName(requestWrapper.getClosedByName() != null
                    ? requestWrapper.getClosedByName()
                    : usersRepo.findById(requestWrapper.getClosedByUserId())
                        .map(u -> u.getName()).orElse(null));
            } else if (lead.getClosedByUserId() == null) {
                // default to current user if not provided
                lead.setClosedByUserId(userId);
                usersRepo.findById(userId).ifPresent(u -> lead.setClosedByName(u.getName()));
            }
        }

        // ── Not Interested reason (set by a non-telecaller user, e.g. BD/Admin) ──
        // Saved into the same telecallerReason column the telecaller flow uses,
        // so it shows up consistently wherever telecallerReason is already displayed.
        if ("Not Interested".equalsIgnoreCase(requestWrapper.getStatus())
                && requestWrapper.getNotInterestedReason() != null
                && !requestWrapper.getNotInterestedReason().trim().isEmpty()) {
            lead.setTelecallerReason(requestWrapper.getNotInterestedReason().trim());
        }

        // ── Keep in View callback date (BD/Admin from the leads page) ──
        if ("Keep in View".equalsIgnoreCase(requestWrapper.getStatus())
                && requestWrapper.getKivReminderDate() != null
                && !requestWrapper.getKivReminderDate().isBlank()) {
            try {
                lead.setKivReminderDate(java.time.LocalDate.parse(
                        requestWrapper.getKivReminderDate().substring(0, 10)));
            } catch (Exception ex) {
                System.err.println("Invalid kivReminderDate '" + requestWrapper.getKivReminderDate() + "' for lead " + leadId);
            }
        }


        // ── Sync telecallerStatus to mirror main status (single source of truth) ──
        if (requestWrapper.getStatus() != null) {
            String newS = requestWrapper.getStatus();
            switch (newS) {
                case "New"           -> lead.setTelecallerStatus("NEW");
                case "Interested"    -> lead.setTelecallerStatus("INTERESTED");
                case "Not Interested"-> lead.setTelecallerStatus("NOT_INTERESTED");
                case "Not Responded" -> lead.setTelecallerStatus("NOT_RESPONDED");
                case "Keep in View"  -> lead.setTelecallerStatus("KEEP_IN_VIEW");
                case "Contacted"     -> lead.setTelecallerStatus("CONTACTED");
                case "In Discussion" -> lead.setTelecallerStatus("IN_DISCUSSION");
                case "Proposal Sent" -> lead.setTelecallerStatus("PROPOSAL_SENT");
                case "Closed Won"    -> lead.setTelecallerStatus("CLOSED_WON");
                case "Closed Lost"   -> lead.setTelecallerStatus("CLOSED_LOST");
                default              -> lead.setTelecallerStatus("NEW");
            }
        }

        // ── Auto-assign BD when source is changed TO "Referral" ───────────────
        boolean sourceChangedToReferral = "Referral".equalsIgnoreCase(requestWrapper.getSource())
                && !("Referral".equalsIgnoreCase(oldSource));
        // Affiliate-team leads must never be auto-assigned to a BD, at any status.
        if (sourceChangedToReferral && lead.getBdAssignedTo() == null
                && !isAffiliateTeamCreator(lead.getCreatedBy())) {
            try {
                Long nextBD = roundRobinService.getNextBD(lead.getGroupName(), lead.getSubGroupName());
                if (nextBD != null) {
                    lead.setBdAssignedTo(nextBD);
                    lead.setBdAssignedAt(LocalDateTime.now());
                    String bdName = usersRepo.findById(nextBD)
                            .map(u -> u.getName()).orElse("Unknown");
                    leadHistoryService.addHistory(
                        leadId, "BD_ASSIGNED", "bdAssignedTo",
                        null, bdName,
                        "BD auto-assigned via round-robin to " + bdName + " (source changed to Referral)",
                        userId
                    );
                }
            } catch (Exception e) {
                System.err.println("Failed to auto-assign BD on referral source change: " + e.getMessage());
            }
        }

        LeadsEntity updatedLead = leadsRepo.save(lead);

        if (requestWrapper.getStatus() != null && !requestWrapper.getStatus().equals(oldStatus)) {
            try {
                leadHistoryService.addHistory(
                    leadId, "STATUS_CHANGED", "status",
                    oldStatus, requestWrapper.getStatus(),
                    "Lead status changed from " + oldStatus + " to " + requestWrapper.getStatus(),
                    userId
                );
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }

        if (isStatusDowngrade
                && requestWrapper.getStatusDowngradeReason() != null
                && !requestWrapper.getStatusDowngradeReason().trim().isEmpty()) {
            String downgradeReason = requestWrapper.getStatusDowngradeReason().trim();
            try {
                leadHistoryService.addHistory(
                    leadId, "STATUS_DOWNGRADE_REASON", "status",
                    oldStatus, requestWrapper.getStatus(),
                    "Reason for moving back from " + oldStatus + " to "
                        + requestWrapper.getStatus() + ": " + downgradeReason,
                    userId
                );
            } catch (Exception e) {
                System.err.println("Failed to add status-downgrade-reason history: " + e.getMessage());
            }
        }

        if ("Closed Lost".equalsIgnoreCase(requestWrapper.getStatus())
                && requestWrapper.getClosedLostReason() != null
                && !requestWrapper.getClosedLostReason().trim().isEmpty()) {
            try {
                leadHistoryService.addHistory(
                    leadId, "CLOSED_LOST_REASON", "closedLostReason",
                    null, requestWrapper.getClosedLostReason().trim(),
                    "Reason for closing as lost: " + requestWrapper.getClosedLostReason().trim(),
                    userId
                );
            } catch (Exception e) {
                System.err.println("Failed to add closed-lost-reason history: " + e.getMessage());
            }
        }

        if ("Not Interested".equalsIgnoreCase(requestWrapper.getStatus())
                && requestWrapper.getNotInterestedReason() != null
                && !requestWrapper.getNotInterestedReason().trim().isEmpty()) {
            String trimmedReason = requestWrapper.getNotInterestedReason().trim();
            try {
                leadHistoryService.addHistory(
                    leadId, "NOT_INTERESTED_REASON", "telecallerReason",
                    null, trimmedReason,
                    "Reason for marking Not Interested: " + trimmedReason,
                    userId
                );
            } catch (Exception e) {
                System.err.println("Failed to add not-interested-reason history: " + e.getMessage());
            }
        }


        if (requestWrapper.getAssignedTo() != null && !requestWrapper.getAssignedTo().equals(oldAssignedTo)) {
            try {
                String oldAssignedName = "Unassigned";
                if (oldAssignedTo != null) {
                    oldAssignedName = usersRepo.findById(oldAssignedTo)
                        .map(u -> u.getName()).orElse("Unknown");
                }
                String newAssignedName = usersRepo.findById(requestWrapper.getAssignedTo())
                    .map(u -> u.getName()).orElse("Unknown");

                leadHistoryService.addHistory(
                    leadId, "ASSIGNED", "assignedTo",
                    oldAssignedName, newAssignedName,
                    "Lead reassigned from " + oldAssignedName + " to " + newAssignedName,
                    userId
                );

                // ── Send email to the newly assigned telecaller ──────────────
                // finalUpdatedLead snapshot required — updatedLead is reassigned
                // later in the method so it is not effectively final for lambdas.
                final LeadsEntity finalUpdatedLead = updatedLead;
                try {
                    usersRepo.findById(requestWrapper.getAssignedTo()).ifPresent(telecaller -> {
                        if (telecaller.getEmail() != null && !telecaller.getEmail().isBlank()) {
                            String subject = "New Lead Assigned to You – " + finalUpdatedLead.getLeadCode();
                            String body = buildSingleLeadEmailBody(telecaller.getName(), finalUpdatedLead);
                            mailService.sendEmail(telecaller.getEmail(), subject, body);
                        }
                    });
                } catch (Exception emailEx) {
                    System.err.println("Failed to send reassignment email: " + emailEx.getMessage());
                }

                // ── NOTIFICATION (independent of email): lead reassigned ──
                try {
                    Long newAssignee = requestWrapper.getAssignedTo();
                    if (newAssignee != null && !newAssignee.equals(userId)) {
                        notificationService.createNotification(
                            newAssignee,
                            "Lead reassigned to you",
                            "Lead " + updatedLead.getLeadCode() + " (" + updatedLead.getName() + ") has been reassigned to you.",
                            Module.LEAD, updatedLead.getId(), Type.LEAD_REASSIGNED);
                    }
                } catch (Exception notifEx) {
                    System.err.println("[LeadsService] notification error on reassign: " + notifEx.getMessage());
                }

            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }

        if (requestWrapper.getPriority() != null && !requestWrapper.getPriority().equals(oldPriority)) {
            try {
                leadHistoryService.addHistory(
                    leadId, "PRIORITY_CHANGED", "priority",
                    oldPriority, requestWrapper.getPriority(),
                    "Lead priority changed from " + oldPriority + " to " + requestWrapper.getPriority(),
                    userId
                );
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }

        // Trigger conversion when:
        //  (a) status just changed TO "Closed Won" — normal path, OR
        //  (b) status is already "Closed Won" but customerId is still null —
        //      this handles the case where a previous attempt crashed after
        //      saving the status but before the customer was created.
        boolean justClosedWon = !"Closed Won".equalsIgnoreCase(oldStatus) &&
                                 "Closed Won".equalsIgnoreCase(updatedLead.getStatus());
        boolean closedWonButNoCustomer = "Closed Won".equalsIgnoreCase(updatedLead.getStatus()) &&
                                          updatedLead.getCustomerId() == null;

        if (justClosedWon || closedWonButNoCustomer) {
            try {
                // ── STEP 1: Convert lead to customer ─────────────────────────
                // Project creation is now deferred: each OrderBook for this
                // client creates its own project (ClientName - OrderBookNo),
                // so one client can have multiple projects across their orders.
                CustomerWrapper customer = customersService.convertLeadToCustomer(updatedLead);

                updatedLead.setCustomerId(customer.getId());
                updatedLead = leadsRepo.save(updatedLead);

                updatedProposal.updateCustomerId(customer.getId(), leadId);

                leadHistoryService.addHistory(
                    leadId, "CONVERTED_TO_CUSTOMER",
                    null, null, customer.getCustomerCode(),
                    "Lead converted to customer: " + customer.getCustomerCode() +
                    ". Create an Order Book to generate the project.",
                    userId
                );

                System.out.println("Lead " + updatedLead.getLeadCode() +
                                 " converted to customer " + customer.getCustomerCode() +
                                 ". Project will be created from Order Book.");
            } catch (Exception e) {
                System.err.println("Failed to convert lead to customer: " + e.getMessage());
                throw new CustomException("Lead status saved but customer conversion failed: " + e.getMessage());
            }
        }

        return convertToWrapper(updatedLead);
    }

    /**
     * Soft delete a lead.
     */
    public void deleteLead(Long leadId, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));

        if (lead.getDeletedAt() != null) {
            throw new CustomException("Lead already deleted");
        }

        if (!hasAccessToLead(lead, userId, userRole)) {
            throw new CustomException("Access denied to delete this lead");
        }

        lead.setDeletedAt(LocalDateTime.now());
        leadsRepo.save(lead);
    }

    /**
     * Get leads by group name with role-based access.
     */
    public List<LeadWrapper> getLeadsByGroup(String groupName, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<LeadsEntity> leads;

        if (level <= 2) {
            leads = leadsRepo.findByGroupNameAndDeletedAtIsNullOrderByCreatedAtDesc(groupName);
        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            leads = leadsRepo.findByTeamMemberLeads(memberIds).stream()
                    .filter(l -> groupName.equals(l.getGroupName()))
                    .collect(Collectors.toList());
        } else {
            leads = leadsRepo.findByCreatedByOrAssignedTo(userId).stream()
                    .filter(lead -> groupName.equals(lead.getGroupName()))
                    .collect(Collectors.toList());
        }

        return leads.stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    /**
     * Get leads by status with role-based access.
     */
    public List<LeadWrapper> getLeadsByStatus(String status, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<LeadsEntity> leads;

        if (level <= 2) {
            leads = leadsRepo.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(status);
        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            leads = leadsRepo.findByTeamMemberLeads(memberIds).stream()
                    .filter(l -> status.equals(l.getStatus()))
                    .collect(Collectors.toList());
        } else {
            leads = leadsRepo.findByCreatedByOrAssignedTo(userId).stream()
                    .filter(lead -> status.equals(lead.getStatus()))
                    .collect(Collectors.toList());
        }

        return leads.stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    /**
     * Get leads assigned to a specific user.
     */
    public List<LeadWrapper> getLeadsAssignedTo(Long assignedUserId, Long requestingUserId, String userRole) throws CustomException {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<LeadsEntity> leads;

        if (level <= 2) {
            leads = leadsRepo.findByAssignedToAndDeletedAtIsNullOrderByCreatedAtDesc(assignedUserId);
        } else if (level == 3) {
            // L3 can see assigned leads of any of their team members
            List<Long> memberIds = resolveTeamMemberIds(requestingUserId);
            if (!memberIds.contains(assignedUserId)) {
                throw new CustomException("Access denied: User is not in your team");
            }
            leads = leadsRepo.findByAssignedToAndDeletedAtIsNullOrderByCreatedAtDesc(assignedUserId);
        } else {
            if (!assignedUserId.equals(requestingUserId)) {
                throw new CustomException("Access denied: Can only view your own assigned leads");
            }
            leads = leadsRepo.findByAssignedToAndDeletedAtIsNullOrderByCreatedAtDesc(assignedUserId);
        }

        return leads.stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    /**
     * Get leads created by a specific user.
     */
    public List<LeadWrapper> getLeadsCreatedBy(Long createdByUserId, Long requestingUserId, String userRole) throws CustomException {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<LeadsEntity> leads;

        if (level <= 2) {
            leads = leadsRepo.findByCreatedByAndDeletedAtIsNullOrderByCreatedAtDesc(createdByUserId);
        } else if (level == 3) {
            // L3 can see leads created by any of their team members
            List<Long> memberIds = resolveTeamMemberIds(requestingUserId);
            if (!memberIds.contains(createdByUserId)) {
                throw new CustomException("Access denied: User is not in your team");
            }
            leads = leadsRepo.findByCreatedByAndDeletedAtIsNullOrderByCreatedAtDesc(createdByUserId);
        } else {
            if (!createdByUserId.equals(requestingUserId)) {
                throw new CustomException("Access denied: Can only view your own created leads");
            }
            leads = leadsRepo.findByCreatedByAndDeletedAtIsNullOrderByCreatedAtDesc(createdByUserId);
        }

        return leads.stream().map(this::convertToWrapper).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Access check for single-lead operations (GET by ID, UPDATE, DELETE).
     * L1/L2 — always allowed.
     * L3     — allowed if lead belongs to any of their team members.
     * L4+    — allowed only for own leads or lead_access entries.
     */
    private boolean hasAccessToLead(LeadsEntity lead, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2) return true;

        // Direct ownership — applies to all levels
        if (userId.equals(lead.getCreatedBy()) || userId.equals(lead.getAssignedTo()))
            return true;

        if (level == 3) {
            // L3 — check if the lead's creator or assignee is in any of their teams
            List<Long> memberIds = resolveTeamMemberIds(userId);
            return memberIds.contains(lead.getCreatedBy())
                || memberIds.contains(lead.getAssignedTo());
        }

        // L4+ — check lead_access table (e.g. BD Executive granted explicit access)
        return leadAccessRepo.existsByLeadIdAndUserId(lead.getId(), userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BULK IMPORT — called by POST /leads/bulk-create
    // Creates every lead with email suppressed, then sends ONE summary email
    // per telecaller covering all leads assigned to them in this batch.
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> bulkCreateLeads(List<LeadRequestWrapper> requests, Long userId) {

        int imported = 0, skipped = 0;
        List<String> errors = new ArrayList<>();

        // telecaller ID → leads assigned to them in this batch
        Map<Long, List<LeadWrapper>> telecallerLeads = new HashMap<>();

        for (int i = 0; i < requests.size(); i++) {
            int displayRow = i + 1;
            try {
                // suppressEmail=true: no individual email per lead
                LeadWrapper lead = createLead(requests.get(i), userId, true);
                imported++;
                if (lead.getAssignedTo() != null) {
                    telecallerLeads
                        .computeIfAbsent(lead.getAssignedTo(), k -> new ArrayList<>())
                        .add(lead);
                }
            } catch (Exception e) {
                skipped++;
                errors.add("Row " + displayRow + ": " + cleanImportError(e.getMessage()));
            }
        }

        // ── Send ONE email per telecaller with all their leads in this batch ──
        for (Map.Entry<Long, List<LeadWrapper>> entry : telecallerLeads.entrySet()) {
            try {
                final List<LeadWrapper> leads = entry.getValue();
                usersRepo.findById(entry.getKey()).ifPresent(telecaller -> {
                    if (telecaller.getEmail() != null && !telecaller.getEmail().isBlank()) {
                        int count = leads.size();
                        String subject = count == 1
                            ? "New Lead Assigned to You \u2013 " + leads.get(0).getLeadCode()
                            : count + " New Leads Assigned to You";
                        String body = buildBulkLeadEmailBodyFromWrappers(telecaller.getName(), leads);
                        mailService.sendEmail(telecaller.getEmail(), subject, body);
                    }
                });
            } catch (Exception e) {
                System.err.println("Bulk import email failed for telecaller "
                        + entry.getKey() + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("skipped",  skipped);
        result.put("errors",   errors);
        return result;
    }

    /**
     * Strips raw Hibernate / JPA SQL from exception messages so the UI only
     * shows a human-readable reason (e.g. "Duplicate phone: 9876543210").
     */
    private String cleanImportError(String raw) {
        if (raw == null) return "Unknown error";
        // Duplicate phone constraint
        if (raw.contains("uq_leads_phone") || raw.contains("Duplicate entry")) {
            Matcher m = Pattern.compile("Duplicate entry '(\\d+)'").matcher(raw);
            if (m.find()) return "Duplicate phone number " + m.group(1) + " \u2014 already exists";
            return "Duplicate phone number \u2014 already exists";
        }
        // Strip SQL payload that starts with " [insert into"
        int cut = raw.indexOf(" [insert into");
        if (cut > 0) raw = raw.substring(0, cut).trim();
        // Strip "[..." bracket detail
        cut = raw.indexOf(" [");
        if (cut > 0) raw = raw.substring(0, cut).trim();
        return raw;
    }

    /** HTML email body for a bulk-import batch (uses LeadWrapper, not entity). */
    private String buildBulkLeadEmailBodyFromWrappers(String telecallerName, List<LeadWrapper> leads) {
        StringBuilder rows = new StringBuilder();
        int idx = 1;
        for (LeadWrapper lead : leads) {
            String bg = (idx % 2 == 0) ? "#f9f9f9" : "#ffffff";
            rows.append("<tr style='background:").append(bg).append("'>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(idx).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getLeadCode())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getName())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getPhone())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getState())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getPriority())).append("</td>")
                .append("</tr>");
            idx++;
        }
        int count = leads.size();
        return "<div style='font-family:Arial,sans-serif;max-width:700px;margin:auto;"
            + "border:1px solid #e0e0e0;border-radius:8px;overflow:hidden'>"
            + "<div style='background:#1a73e8;padding:20px 24px'>"
            + "<h2 style='color:#fff;margin:0;font-size:20px'>"
            + count + " New Lead" + (count > 1 ? "s" : "") + " Assigned to You</h2></div>"
            + "<div style='padding:24px'>"
            + "<p style='margin:0 0 16px'>Hi <strong>" + escapeHtml(telecallerName) + "</strong>,</p>"
            + "<p style='margin:0 0 16px'><strong>" + count + "</strong> new lead"
            + (count > 1 ? "s have" : " has") + " been assigned to you:</p>"
            + "<table style='width:100%;border-collapse:collapse;font-size:13px'>"
            + "<thead><tr style='background:#1a73e8;color:#fff'>"
            + "<th style='padding:10px;text-align:left'>#</th>"
            + "<th style='padding:10px;text-align:left'>Lead Code</th>"
            + "<th style='padding:10px;text-align:left'>Client Name</th>"
            + "<th style='padding:10px;text-align:left'>Phone</th>"
            + "<th style='padding:10px;text-align:left'>State</th>"
            + "<th style='padding:10px;text-align:left'>Priority</th>"
            + "</tr></thead><tbody>" + rows + "</tbody></table>"
            + "<p style='margin:20px 0 0;font-size:13px;color:#666'>"
            + "Please log in to the CRM to view full details and take action.</p>"
            + "<p style='margin:12px 0 0;font-size:13px'>"
            + "<a href='https://crm.sesolaenergy.com/' "
            + "style='display:inline-block;background:#1a73e8;color:#fff;padding:10px 22px;"
            + "border-radius:6px;text-decoration:none;font-weight:bold;font-size:13px'>"
            + "Open CRM &rarr;</a></p>"
            + "</div>"
            + "<div style='background:#f5f5f5;padding:12px 24px;font-size:12px;color:#999'>"
            + "SESOLA Group CRM &mdash; This is an automated notification.</div></div>";
    }

    /** Converts empty/null strings to null so JPQL :param IS NULL checks work. */
    private String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /**
     * Builds an HTML email body for a single newly-assigned lead.
     */
    private String buildSingleLeadEmailBody(String telecallerName, LeadsEntity lead) {
        return "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;border:1px solid #e0e0e0;border-radius:8px;overflow:hidden'>"
            + "<div style='background:#1a73e8;padding:20px 24px'>"
            + "<h2 style='color:#fff;margin:0;font-size:20px'>New Lead Assigned to You</h2>"
            + "</div>"
            + "<div style='padding:24px'>"
            + "<p style='margin:0 0 16px'>Hi <strong>" + escapeHtml(telecallerName) + "</strong>,</p>"
            + "<p style='margin:0 0 16px'>A new lead has been assigned to you. Please find the details below:</p>"
            + "<table style='width:100%;border-collapse:collapse;font-size:14px'>"
            + buildLeadRow("Lead Code",   lead.getLeadCode())
            + buildLeadRow("Client Name", lead.getName())
            + buildLeadRow("Phone",       lead.getPhone())
            + buildLeadRow("Email",       lead.getEmail())
            + buildLeadRow("Source",      lead.getSource())
            + buildLeadRow("Priority",    lead.getPriority())
            + buildLeadRow("Group",       lead.getGroupName())
            + buildLeadRow("Sub-Group",   lead.getSubGroupName())
            + buildLeadRow("State",       lead.getState())
            + buildLeadRow("Enquiry",     lead.getEnquiry())
            + "</table>"
            + "<p style='margin:20px 0 0;font-size:13px;color:#666'>Please log in to the CRM to view full details and take action.</p>"
            + "<p style='margin:12px 0 0;font-size:13px'>"
            + "<a href='https://crm.sesolaenergy.com/' "
            + "style='display:inline-block;background:#1a73e8;color:#fff;padding:10px 22px;"
            + "border-radius:6px;text-decoration:none;font-weight:bold;font-size:13px'>"
            + "Open CRM &rarr;</a></p>"
            + "</div>"
            + "<div style='background:#f5f5f5;padding:12px 24px;font-size:12px;color:#999'>SESOLA Group CRM &mdash; This is an automated notification.</div>"
            + "</div>";
    }

    /**
     * Builds an HTML email body for multiple leads assigned in a bulk import.
     * One email per telecaller listing all their newly assigned leads.
     */
//    private String buildBulkLeadEmailBody(String telecallerName, java.util.List<LeadsEntity> leads) {
//        StringBuilder rows = new StringBuilder();
//        int index = 1;
//        for (LeadsEntity lead : leads) {
//            String bg = (index % 2 == 0) ? "#f9f9f9" : "#ffffff";
//            rows.append("<tr style='background:").append(bg).append("'>")
//                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(index).append("</td>")
//                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getLeadCode())).append("</td>")
//                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getName())).append("</td>")
//                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getPhone())).append("</td>")
//                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getState())).append("</td>")
//                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getPriority())).append("</td>")
//                .append("</tr>");
//            index++;
//        }
//
//        return "<div style='font-family:Arial,sans-serif;max-width:700px;margin:auto;border:1px solid #e0e0e0;border-radius:8px;overflow:hidden'>"
//            + "<div style='background:#1a73e8;padding:20px 24px'>"
//            + "<h2 style='color:#fff;margin:0;font-size:20px'>" + leads.size() + " New Lead" + (leads.size() > 1 ? "s" : "") + " Assigned to You</h2>"
//            + "</div>"
//            + "<div style='padding:24px'>"
//            + "<p style='margin:0 0 16px'>Hi <strong>" + escapeHtml(telecallerName) + "</strong>,</p>"
//            + "<p style='margin:0 0 16px'><strong>" + leads.size() + "</strong> new lead" + (leads.size() > 1 ? "s have" : " has") + " been assigned to you via bulk import:</p>"
//            + "<table style='width:100%;border-collapse:collapse;font-size:13px'>"
//            + "<thead><tr style='background:#1a73e8;color:#fff'>"
//            + "<th style='padding:10px;text-align:left'>#</th>"
//            + "<th style='padding:10px;text-align:left'>Lead Code</th>"
//            + "<th style='padding:10px;text-align:left'>Client Name</th>"
//            + "<th style='padding:10px;text-align:left'>Phone</th>"
//            + "<th style='padding:10px;text-align:left'>State</th>"
//            + "<th style='padding:10px;text-align:left'>Priority</th>"
//            + "</tr></thead>"
//            + "<tbody>" + rows + "</tbody>"
//            + "</table>"
//            + "<p style='margin:20px 0 0;font-size:13px;color:#666'>Please log in to the CRM to view full details and take action on your leads.</p>"
//            + "<p style='margin:12px 0 0;font-size:13px'>"
//            + "<a href='https://crm.sesolaenergy.com/' "
//            + "style='display:inline-block;background:#1a73e8;color:#fff;padding:10px 22px;"
//            + "border-radius:6px;text-decoration:none;font-weight:bold;font-size:13px'>"
//            + "Open CRM &rarr;</a></p>"
//            + "</div>"
//            + "<div style='background:#f5f5f5;padding:12px 24px;font-size:12px;color:#999'>SESOLA Group CRM &mdash; This is an automated notification.</div>"
//            + "</div>";
//    }

    private String buildLeadRow(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<tr>"
            + "<td style='padding:8px 10px;font-weight:bold;background:#f5f7ff;border-bottom:1px solid #e8ecff;width:35%'>" + label + "</td>"
            + "<td style='padding:8px 10px;border-bottom:1px solid #e8ecff'>" + escapeHtml(value) + "</td>"
            + "</tr>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private LeadWrapper convertToWrapper(LeadsEntity entity) {
        LeadWrapper wrapper = new LeadWrapper();
        wrapper.setId(entity.getId());
        wrapper.setLeadCode(entity.getLeadCode());
        wrapper.setCustomerId(entity.getCustomerId());
        wrapper.setName(entity.getName());
        wrapper.setEmail(entity.getEmail());
        wrapper.setPhone(entity.getPhone());
        wrapper.setSource(entity.getSource());
        wrapper.setPriority(entity.getPriority());
        wrapper.setStatus(entity.getStatus());
        wrapper.setEnquiry(entity.getEnquiry());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        wrapper.setAssignedTo(entity.getAssignedTo());
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);

        // Address
        wrapper.setState(entity.getState());
        wrapper.setDistrict(entity.getDistrict());
        wrapper.setCity(entity.getCity());
        wrapper.setPincode(entity.getPincode());

        // Solar
        wrapper.setSolarScheme(entity.getSolarScheme());

        // Referral
        wrapper.setReferralName(entity.getReferralName());
        wrapper.setReferralPhone(entity.getReferralPhone());

        // Capacity
        wrapper.setCapacity(entity.getCapacity());
        wrapper.setCapacityUnit(entity.getCapacityUnit());

        // Lead Owner — set name, and also resolve user id + avatar for reliable frontend display
        wrapper.setLeadOwner(entity.getLeadOwner());
        if (entity.getLeadOwner() != null && !entity.getLeadOwner().isBlank()) {
            usersRepo.findByNameIgnoreCase(entity.getLeadOwner().trim()).stream().findFirst()
                .ifPresent(u -> {
                    wrapper.setLeadOwnerUserId(u.getId());
                    wrapper.setLeadOwnerAvatarUrl(u.getAvatar_url());
                });
        }
        // Telecaller info
        wrapper.setTelecallerStatus(
            entity.getTelecallerStatus() == null ? "NEW" : entity.getTelecallerStatus());
        wrapper.setTelecallerReason(entity.getTelecallerReason());
        wrapper.setKivReminderDate(
            entity.getKivReminderDate() != null ? entity.getKivReminderDate().toString() : null);
        wrapper.setTcDiscussionNote(entity.getTcDiscussionNote());
        wrapper.setTcLocation(entity.getTcLocation());
        wrapper.setTcSiteVisitDate(
            entity.getTcSiteVisitDate() != null ? entity.getTcSiteVisitDate().toString() : null);
        wrapper.setTcPropertyType(entity.getTcPropertyType());
        wrapper.setTcQuotedPrice(entity.getTcQuotedPrice());
        wrapper.setTcAddons(entity.getTcAddons());
        wrapper.setTcOtherComments(entity.getTcOtherComments());

        // New INTERESTED fields
        wrapper.setTcMonthlyBill(entity.getTcMonthlyBill());
        wrapper.setTcExistingContractLoad(entity.getTcExistingContractLoad());
        wrapper.setTcRequiredContractLoad(entity.getTcRequiredContractLoad());
        // Bill file metadata — never send raw BLOB bytes in list responses
        wrapper.setTcBillFileName(entity.getTcBillFileName());
        wrapper.setTcBillFileType(entity.getTcBillFileType());
        wrapper.setTcHasBillFile(entity.isTcHasBillFile());

        // assignedToName = generic owner label (telecaller OR senior owner).
        // telecallerName = ONLY set when the assignee is actually a TELECALLER,
        // so the UI never mislabels a senior owner / BD as a telecaller.
        if (entity.getAssignedTo() != null) {
            usersRepo.findById(entity.getAssignedTo()).ifPresent(user -> {
                wrapper.setAssignedToName(user.getName());
                if ("TELECALLER".equalsIgnoreCase(user.getRole())) {
                    wrapper.setTelecallerName(user.getName());
                }
            });
        }

        // BD info
        if (entity.getBdAssignedTo() != null) {
            wrapper.setBdAssignedToId(entity.getBdAssignedTo());
            usersRepo.findById(entity.getBdAssignedTo())
                     .ifPresent(u -> wrapper.setBdAssignedToName(u.getName()));
            wrapper.setBdAssignedAt(
                entity.getBdAssignedAt() != null ? entity.getBdAssignedAt().toString() : null);
        }

        // Closed Won attribution
        wrapper.setClosedByUserId(entity.getClosedByUserId());
        wrapper.setClosedByName(entity.getClosedByName());

        if (entity.getCreatedBy() != null) {
            usersRepo.findById(entity.getCreatedBy())
                     .ifPresent(user -> wrapper.setCreatedByName(user.getName()));
        }

        try {
            boolean hasPending = followupsService.hasLeadPendingFollowups(entity.getId());
            int count = followupsService.getPendingFollowupsCountForLead(entity.getId());
            wrapper.setHasPendingFollowups(hasPending);
            wrapper.setPendingFollowupsCount(count);
        } catch (Exception e) {
            wrapper.setHasPendingFollowups(false);
            wrapper.setPendingFollowupsCount(0);
        }

        return wrapper;
    }

    // ── Bill file BLOB storage ─────────────────────────────────────────────────

    @org.springframework.transaction.annotation.Transactional
    public void saveBillFileToDb(Long leadId, Long userId, String userRole,
                                  org.springframework.web.multipart.MultipartFile file)
            throws Exception {

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found: " + leadId));
        if (lead.getDeletedAt() != null)
            throw new CustomException("Lead not found");

        lead.setTcBillFileData(file.getBytes());
        lead.setTcBillFileName(file.getOriginalFilename());
        lead.setTcBillFileType(
            file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        lead.setTcHasBillFile(true); // set flag so list queries show the view button
        leadsRepo.save(lead);
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
}