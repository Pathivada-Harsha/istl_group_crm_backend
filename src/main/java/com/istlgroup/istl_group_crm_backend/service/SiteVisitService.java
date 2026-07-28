package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.SiteVisitEntity;
import com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.SiteVisitRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SiteVisitRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SiteVisitWrapper;

@Service
public class SiteVisitService {

    @Autowired
    private SiteVisitRepo siteVisitRepo;

    @Autowired
    private LeadsRepo leadsRepo;

    @Autowired
    private FollowupsRepo followupsRepo;

    @Autowired
    private LeadAccessRepo leadAccessRepo;

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private LeadHistoryService leadHistoryService;

    @Autowired
    private RoleHierarchyService roleHierarchyService;

    // ─────────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────────

    public List<SiteVisitWrapper> getByLead(Long leadId) {
        LeadsEntity lead = leadsRepo.findById(leadId).orElse(null);
        return siteVisitRepo.findByLeadIdOrderByVisitDateDescIdDesc(leadId)
                .stream().map(v -> convertToWrapper(v, lead)).collect(Collectors.toList());
    }

    /** Distinct list of users who have visited this lead's site(s). */
    public List<Map<String, Object>> getVisitedUsers(Long leadId) {
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (SiteVisitEntity v : siteVisitRepo.findByLeadIdOrderByVisitDateDescIdDesc(leadId)) {
            if (v.getVisitedByUserId() == null || byUser.containsKey(v.getVisitedByUserId())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", v.getVisitedByUserId());
            entry.put("name", v.getVisitedByName() != null
                    ? v.getVisitedByName() : usersRepo.findUserNameWithUserId(v.getVisitedByUserId()));
            entry.put("lastVisitDate", v.getVisitDate() != null ? v.getVisitDate().toString() : null);
            byUser.put(v.getVisitedByUserId(), entry);
        }
        return new ArrayList<>(byUser.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────

    public SiteVisitWrapper create(SiteVisitRequestWrapper request, Long userId, String userRole)
            throws CustomException {

        if (request.getLeadId() == null) throw new CustomException("leadId is required");
        Long leadId = request.getLeadId();

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        authorize(request, leadId, userId, userRole);

        // One report per lead — upsert. If a report already exists, update it in
        // place regardless of entry point (tab or the assigned visitor's follow-up).
        List<SiteVisitEntity> existing = siteVisitRepo.findByLeadIdOrderByVisitDateDescIdDesc(leadId);
        SiteVisitEntity visit = existing.isEmpty() ? new SiteVisitEntity() : existing.get(0);
        visit.setLeadId(leadId);
        if (request.getFollowupId() != null) visit.setFollowupId(request.getFollowupId());
        visit.setVisitedByUserId(userId);
        visit.setVisitedByName(usersRepo.findUserNameWithUserId(userId));
        if (visit.getCreatedBy() == null) visit.setCreatedBy(userId);
        if (request.getVisitDate() != null && !request.getVisitDate().isBlank()) {
            visit.setVisitDate(LocalDate.parse(request.getVisitDate()));
        } else if (visit.getVisitDate() == null) {
            visit.setVisitDate(LocalDate.now());
        }
        applyReportFields(visit, request);

        SiteVisitEntity saved = siteVisitRepo.save(visit);

        // Single source of truth: push overlapping fields back onto the lead.
        writeBackToLead(lead, request, saved);

        // Complete the originating "Visit" follow-up, if any.
        if (request.getFollowupId() != null) {
            completeFollowup(request.getFollowupId(), userId);
        }

        // Lead history entry.
        try {
            String description = String.format(
                    "Site visit report recorded by %s on %s",
                    visit.getVisitedByName() != null ? visit.getVisitedByName() : ("User #" + userId),
                    visit.getVisitDate() != null
                            ? visit.getVisitDate().format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                            : "-");
            leadHistoryService.addHistory(leadId, "SITE_VISIT_ADDED", null, null, null, description, userId);
        } catch (Exception e) {
            System.err.println("[SiteVisitService] failed to add history: " + e.getMessage());
        }

        return convertToWrapper(saved, lead);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    public SiteVisitWrapper update(Long id, SiteVisitRequestWrapper request, Long userId, String userRole)
            throws CustomException {

        SiteVisitEntity visit = siteVisitRepo.findById(id)
                .orElseThrow(() -> new CustomException("Site visit not found"));

        Long leadId = visit.getLeadId();
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        // Reuse the same auth rule; the stored followupId governs the visitor path.
        if (request.getFollowupId() == null) request.setFollowupId(visit.getFollowupId());
        authorize(request, leadId, userId, userRole);

        if (request.getVisitDate() != null && !request.getVisitDate().isBlank()) {
            visit.setVisitDate(LocalDate.parse(request.getVisitDate()));
        }
        applyReportFields(visit, request);
        SiteVisitEntity saved = siteVisitRepo.save(visit);

        writeBackToLead(lead, request, saved);

        return convertToWrapper(saved, lead);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Same rule as {@link LeadScopeService}#authorize / {@code LeadsService#hasAccessToLead}:
     * level ≤ 2 bypass (L1/L2), else the lead's creator/assignee, else an explicit
     * lead_access grant — PLUS the visitor path: the assignee of the Visit follow-up
     * this report is being filed against may write the report without full lead access.
     */
    private void authorize(SiteVisitRequestWrapper request, Long leadId, Long userId, String userRole)
            throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId).orElse(null);

        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2) return;

        if (lead != null && userId != null
                && (userId.equals(lead.getCreatedBy()) || userId.equals(lead.getAssignedTo()))) {
            return;
        }

        if (leadAccessRepo.existsByLeadIdAndUserId(leadId, userId)) return;

        if (request.getFollowupId() != null) {
            FollowupsEntity fu = followupsRepo.findById(request.getFollowupId()).orElse(null);
            if (fu != null && userId.equals(fu.getAssignedTo())) {
                // Normally the follow-up's leadId matches; fall back to relatedType/relatedId
                // so a Visit follow-up with a null leadId column still authorizes its assignee.
                boolean leadMatches = leadId.equals(fu.getLeadId())
                        || ("LEAD".equalsIgnoreCase(fu.getRelatedType()) && leadId.equals(fu.getRelatedId()));
                if (leadMatches) return;
            }
        }

        throw new CustomException("You are not authorized to record a site visit for this lead");
    }

    private void completeFollowup(Long followupId, Long userId) {
        try {
            FollowupsEntity fu = followupsRepo.findById(followupId).orElse(null);
            if (fu == null) return;
            if (!"Completed".equals(fu.getStatus())) {
                fu.setStatus("Completed");
                fu.setCompletedAt(LocalDateTime.now());
                if (fu.getOutcome() == null || fu.getOutcome().isBlank()) {
                    fu.setOutcome("Site Visit Report submitted");
                }
                followupsRepo.save(fu);
                if (fu.getLeadId() != null) {
                    leadHistoryService.addHistory(fu.getLeadId(), "FOLLOWUP_COMPLETED",
                            null, null, null, "Follow-up completed: Visit — Site Visit Report submitted", userId);
                }
            }
        } catch (Exception e) {
            System.err.println("[SiteVisitService] failed to complete follow-up: " + e.getMessage());
        }
    }

    /** Copy the site-visit-specific report fields from the request onto the entity. */
    private void applyReportFields(SiteVisitEntity visit, SiteVisitRequestWrapper r) {
        visit.setSiteAddress(r.getSiteAddress());
        visit.setSiteLat(r.getSiteLat());
        visit.setSiteLng(r.getSiteLng());

        visit.setSanctionedLoad(r.getSanctionedLoad());
        visit.setElectricityTariff(r.getElectricityTariff());
        visit.setConsumerNumber(r.getConsumerNumber());
        visit.setDiscom(r.getDiscom());
        visit.setPhase(r.getPhase());
        visit.setMonthlyConsumptionUnits(r.getMonthlyConsumptionUnits());

        visit.setRoofType(r.getRoofType());
        visit.setShadowFreeArea(r.getShadowFreeArea());
        visit.setRoofCondition(r.getRoofCondition());
        visit.setRoofDirection(r.getRoofDirection());
        visit.setRoofTilt(r.getRoofTilt());
        visit.setAccessibility(r.getAccessibility());

        visit.setShadowAnalysis(r.getShadowAnalysis());
        visit.setShadowRemarks(r.getShadowRemarks());

        visit.setInverterLocation(r.getInverterLocation());
        visit.setAcCableLength(r.getAcCableLength());
        visit.setDcCableLength(r.getDcCableLength());
        visit.setEarthingLocation(r.getEarthingLocation());
        visit.setStructureType(r.getStructureType());
        visit.setModuleOrientation(r.getModuleOrientation());
    }

    /** Overlapping fields live on the lead — update them there when provided. */
    private void writeBackToLead(LeadsEntity lead, SiteVisitRequestWrapper r, SiteVisitEntity visit) {
        boolean dirty = false;
        if (notBlank(r.getMonthlyBill()))          { lead.setTcMonthlyBill(r.getMonthlyBill().trim()); dirty = true; }
        if (notBlank(r.getExistingContractLoad())) { lead.setTcExistingContractLoad(r.getExistingContractLoad().trim()); dirty = true; }
        if (notBlank(r.getRequiredContractLoad())) { lead.setTcRequiredContractLoad(r.getRequiredContractLoad().trim()); dirty = true; }
        if (notBlank(r.getCapacity()))             { lead.setCapacity(r.getCapacity().trim()); dirty = true; }
        if (notBlank(r.getCapacityUnit()))         { lead.setCapacityUnit(r.getCapacityUnit().trim()); dirty = true; }
        if (notBlank(r.getPropertyType()))         { lead.setTcPropertyType(r.getPropertyType().trim()); dirty = true; }
        if (notBlank(r.getSiteAddress()))          { lead.setTcLocation(trimTo(r.getSiteAddress().trim(), 255)); dirty = true; }
        // Reflect the visit date on the lead so the Overview "Site Visit Date" updates.
        if (visit != null && visit.getVisitDate() != null) { lead.setTcSiteVisitDate(visit.getVisitDate()); dirty = true; }
        if (dirty) leadsRepo.save(lead);
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private String trimTo(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    private SiteVisitWrapper convertToWrapper(SiteVisitEntity v, LeadsEntity lead) {
        SiteVisitWrapper w = new SiteVisitWrapper();
        w.setId(v.getId());
        w.setLeadId(v.getLeadId());
        w.setFollowupId(v.getFollowupId());
        w.setVisitedByUserId(v.getVisitedByUserId());
        w.setVisitedByName(v.getVisitedByName());
        w.setVisitDate(v.getVisitDate() != null ? v.getVisitDate().toString() : null);

        w.setSiteAddress(v.getSiteAddress());
        w.setSiteLat(v.getSiteLat());
        w.setSiteLng(v.getSiteLng());

        w.setSanctionedLoad(v.getSanctionedLoad());
        w.setElectricityTariff(v.getElectricityTariff());
        w.setConsumerNumber(v.getConsumerNumber());
        w.setDiscom(v.getDiscom());
        w.setPhase(v.getPhase());
        w.setMonthlyConsumptionUnits(v.getMonthlyConsumptionUnits());

        w.setRoofType(v.getRoofType());
        w.setShadowFreeArea(v.getShadowFreeArea());
        w.setRoofCondition(v.getRoofCondition());
        w.setRoofDirection(v.getRoofDirection());
        w.setRoofTilt(v.getRoofTilt());
        w.setAccessibility(v.getAccessibility());

        w.setShadowAnalysis(v.getShadowAnalysis());
        w.setShadowRemarks(v.getShadowRemarks());

        w.setInverterLocation(v.getInverterLocation());
        w.setAcCableLength(v.getAcCableLength());
        w.setDcCableLength(v.getDcCableLength());
        w.setEarthingLocation(v.getEarthingLocation());
        w.setStructureType(v.getStructureType());
        w.setModuleOrientation(v.getModuleOrientation());

        w.setCreatedBy(v.getCreatedBy());
        if (v.getCreatedBy() != null) w.setCreatedByName(usersRepo.findUserNameWithUserId(v.getCreatedBy()));
        w.setCreatedAt(v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
        w.setUpdatedAt(v.getUpdatedAt() != null ? v.getUpdatedAt().toString() : null);

        // Echo overlapping fields from the lead so the report renders complete.
        if (lead != null) {
            w.setMonthlyBill(lead.getTcMonthlyBill());
            w.setExistingContractLoad(lead.getTcExistingContractLoad());
            w.setRequiredContractLoad(lead.getTcRequiredContractLoad());
            w.setCapacity(lead.getCapacity());
            w.setCapacityUnit(lead.getCapacityUnit());
            w.setPropertyType(lead.getTcPropertyType());
        }

        return w;
    }
}
