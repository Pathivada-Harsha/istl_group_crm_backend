package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.TemplateLineVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetExtraEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.ScopeActivitySuggestionEntity;
import com.istlgroup.istl_group_crm_backend.entity.SiteVisitEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.TemplateLineVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBudgetExtraRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBudgetItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBudgetRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.ScopeActivitySuggestionRepo;
import com.istlgroup.istl_group_crm_backend.repo.SiteVisitRepo;
import com.istlgroup.istl_group_crm_backend.service.scope.ScopeSubItems;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;
import com.istlgroup.istl_group_crm_backend.util.VariantAttributes;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetCategoryRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.CapacityRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ExtraLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ExtrasSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemsBulkRequest;

/**
 * Lead Technical Scope + Budget Estimation service.
 *
 * Access follows the same rule as SiteVisitService#authorize / LeadsService
 * #hasAccessToLead: level ≤ 2 bypass, else creator/assignee, else an explicit
 * lead_access grant.
 */
@Service
public class LeadScopeService {

    @Autowired
    private LeadScopeRepo leadScopeRepo;

    @Autowired
    private LeadScopeItemRepo leadScopeItemRepo;

    @Autowired
    private LeadBudgetRepo leadBudgetRepo;

    @Autowired
    private LeadBudgetItemRepo leadBudgetItemRepo;

    @Autowired
    private LeadBomRepo leadBomRepo;

    @Autowired
    private LeadBudgetExtraRepo leadBudgetExtraRepo;

    @Autowired
    private LeadScopeTemplateRepo leadScopeTemplateRepo;

    @Autowired
    private LeadScopeTemplateItemRepo leadScopeTemplateItemRepo;

    @Autowired
    private LeadBomTemplateItemRepo leadBomTemplateItemRepo;

    @Autowired
    private BomItemVariantRepo bomItemVariantRepo;

    @Autowired
    private BomItemsMasterRepo bomItemsMasterRepo;

    @Autowired
    private TemplateLineVariantRepo templateLineVariantRepo;

    @Autowired
    private SiteVisitRepo siteVisitRepo;

    @Autowired
    private LeadSuggestionEngine suggestionEngine;

    /**
     * Borrowed for one question only: "can this template line produce a quantity
     * today?" — asked when deciding whether re-running the suggestion would
     * actually fix a lead's blank line. Reusing the admin's verdict keeps that
     * judgement in one place instead of a second copy that can drift.
     */
    @Autowired
    private LeadAdminService leadAdminService;

    @Autowired
    private LeadsRepo leadsRepo;

    @Autowired
    private RoleHierarchyService roleHierarchyService;

    @Autowired
    private LeadAccessRepo leadAccessRepo;

    /** L3 team scoping — the same source LeadsService uses for list visibility. */
    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ScopeActivitySuggestionRepo scopeActivitySuggestionRepo;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScopeSubItems scopeSubItems;

    // ─────────────────────────────────────────────────────────────────────────
    // AUTH
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Who may edit a lead's scope / BOM / budget.
     *
     * <p>Mirrors the lead-visibility rule {@link LeadsService} already applies to
     * the list — L1/L2 every lead, L3 their team's leads, L4 and below their own —
     * so a lead you can open is a lead you can estimate. Before, this only let
     * L1/L2 and the lead's creator/assignee through, which left an L3 manager
     * looking at a team lead with every tab visible and every action rejected.
     *
     * <p>An explicit {@code lead_access} grant still works at any level.
     */
    private void authorize(Long leadId, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2) return;                       // L1 / L2 — every lead

        if (ownsLead(lead, userId)) return;           // own lead, any level

        // L3 — anything belonging to someone they share a team with. Matches
        // LeadsRepo.findByTeamMemberLeads, which is what put the lead on their
        // list in the first place.
        if (level == 3) {
            List<Long> teamMemberIds = teamRepository.findTeamMemberIdsByUserId(userId);
            if (teamMemberIds != null) {
                for (Long memberId : teamMemberIds) {
                    if (ownsLead(lead, memberId)) return;
                }
            }
        }

        if (leadAccessRepo.existsByLeadIdAndUserId(leadId, userId)) return;

        throw new CustomException("You are not authorized to access this lead's estimation");
    }

    /**
     * Creator, assignee, or the BD executive the lead was routed to. The BD leg
     * matters because round-robin sets only {@code bdAssignedTo} — without it the
     * executive who owns the lead in practice cannot price it.
     */
    private boolean ownsLead(LeadsEntity lead, Long userId) {
        if (userId == null) return false;
        return userId.equals(lead.getCreatedBy())
                || userId.equals(lead.getAssignedTo())
                || userId.equals(lead.getBdAssignedTo());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCOPE
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getScope(Long leadId) {
        LeadScopeEntity header = leadScopeRepo.findByLeadIdAndDeletedAtIsNull(leadId).orElse(null);
        List<LeadScopeItemEntity> items =
                leadScopeItemRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);

        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (LeadScopeItemEntity it : items) itemMaps.add(scopeItemMap(it));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("header", header != null ? scopeHeaderMap(header) : null);
        data.put("items", itemMaps);
        return data;
    }

    public Map<String, Object> saveScopeHeader(Long leadId, ScopeHeaderRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        LeadScopeEntity header = leadScopeRepo.findByLeadIdAndDeletedAtIsNull(leadId)
                .orElseGet(() -> {
                    LeadScopeEntity h = new LeadScopeEntity();
                    h.setLeadId(leadId);
                    h.setCreatedBy(userId);
                    return h;
                });

        header.setProjectType(body.getProjectType());
        header.setSystemCapacity(body.getSystemCapacity());
        header.setScopeOfWork(body.getScopeOfWork());
        header.setTechnicalNotes(body.getTechnicalNotes());
        header.setSiteLocation(body.getSiteLocation());

        LeadScopeEntity saved = leadScopeRepo.save(header);
        return scopeHeaderMap(saved);
    }

    public Map<String, Object> saveScopeItem(Long leadId, ScopeItemRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        if (body.getActivity() == null || body.getActivity().isBlank()) {
            throw new CustomException("activity is required");
        }

        LeadScopeItemEntity item;
        if (body.getId() != null) {
            item = leadScopeItemRepo.findById(body.getId())
                    .orElseThrow(() -> new CustomException("Scope item not found"));
            if (!leadId.equals(item.getLeadId())) throw new CustomException("Scope item does not belong to this lead");
        } else {
            item = new LeadScopeItemEntity();
            item.setLeadId(leadId);
            item.setCreatedBy(userId);
        }

        item.setSeqNo(body.getSeqNo() != null ? body.getSeqNo() : 1);
        item.setActivity(body.getActivity().trim());
        item.setCategory(body.getCategory());
        item.setSpecification(body.getSpecification());
        item.setQuantity(body.getQuantity());
        item.setUnit(body.getUnit());
        item.setNotes(body.getNotes());

        LeadScopeItemEntity saved = leadScopeItemRepo.save(item);

        bumpActivitySuggestion(saved.getActivity());

        return scopeItemMap(saved);
    }

    /**
     * Whole-list replace of a lead's scope lines. Lines absent from the payload
     * are soft-deleted; the rest are upserted in the order supplied. Mirrors the
     * projects module's replace-all scope save, and is what the redesigned
     * Technical Scope tab uses instead of the per-row save/delete pair above.
     */
    public List<Map<String, Object>> saveScopeItems(Long leadId, ScopeItemsBulkRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        List<ScopeItemRequest> incoming = body.getItems() != null ? body.getItems() : List.of();

        // Validate everything before writing anything — a bad row must not leave
        // the list half-saved.
        for (ScopeItemRequest r : incoming) {
            if (r.getActivity() == null || r.getActivity().isBlank()) {
                throw new CustomException("Every scope line needs an activity");
            }
            // Each parent's breakdown is its own weight group, validated per line so the
            // message can name the activity to go and fix. Same rules as the template.
            scopeSubItems.normaliseWeights(r.getActivity().trim(), r.getSubItems());
        }

        List<LeadScopeItemEntity> existing =
                leadScopeItemRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        Map<Long, LeadScopeItemEntity> byId = new LinkedHashMap<>();
        for (LeadScopeItemEntity it : existing) byId.put(it.getId(), it);

        Set<Long> keptIds = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 1;

        for (ScopeItemRequest r : incoming) {
            LeadScopeItemEntity item;
            if (r.getId() != null) {
                item = byId.get(r.getId());
                if (item == null) throw new CustomException("Scope item does not belong to this lead");
            } else {
                item = new LeadScopeItemEntity();
                item.setLeadId(leadId);
                item.setCreatedBy(userId);
            }

            item.setSeqNo(seq++);
            item.setActivity(r.getActivity().trim());
            item.setCategory(r.getCategory());
            item.setSpecification(r.getSpecification());
            item.setQuantity(r.getQuantity());
            item.setUnit(r.getUnit());
            item.setNotes(r.getNotes());
            item.setSubItems(scopeSubItems.serialise(r.getSubItems()));

            LeadScopeItemEntity saved = leadScopeItemRepo.save(item);
            keptIds.add(saved.getId());
            bumpActivitySuggestion(saved.getActivity());
            out.add(scopeItemMap(saved));
        }

        // Soft-delete whatever the client dropped.
        LocalDateTime now = LocalDateTime.now();
        for (LeadScopeItemEntity it : existing) {
            if (!keptIds.contains(it.getId())) {
                it.setDeletedAt(now);
                leadScopeItemRepo.save(it);
            }
        }

        return out;
    }

    public void deleteScopeItem(Long leadId, Long itemId, Long userId, String userRole) throws CustomException {
        authorize(leadId, userId, userRole);
        LeadScopeItemEntity item = leadScopeItemRepo.findById(itemId)
                .orElseThrow(() -> new CustomException("Scope item not found"));
        if (!leadId.equals(item.getLeadId())) throw new CustomException("Scope item does not belong to this lead");
        item.setDeletedAt(java.time.LocalDateTime.now());
        leadScopeItemRepo.save(item);
    }

    private void bumpActivitySuggestion(String name) {
        if (name == null || name.isBlank() || name.length() > 255) return;
        scopeActivitySuggestionRepo.findByNameIgnoreCase(name).ifPresentOrElse(
                e -> { e.setUseCount(e.getUseCount() + 1); scopeActivitySuggestionRepo.save(e); },
                () -> {
                    ScopeActivitySuggestionEntity e = new ScopeActivitySuggestionEntity();
                    e.setName(name);
                    scopeActivitySuggestionRepo.save(e);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUDGET
    // ─────────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getBudget(Long leadId) {
        List<LeadBudgetEntity> categories =
                leadBudgetRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LeadBudgetEntity cat : categories) out.add(budgetCategoryMap(cat));
        return out;
    }

    public Map<String, Object> saveBudgetCategory(Long leadId, BudgetCategoryRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        if (body.getCategory() == null || body.getCategory().isBlank()) {
            throw new CustomException("category is required");
        }

        LeadBudgetEntity cat;
        if (body.getId() != null) {
            cat = leadBudgetRepo.findById(body.getId())
                    .orElseThrow(() -> new CustomException("Budget category not found"));
            if (!leadId.equals(cat.getLeadId())) throw new CustomException("Budget category does not belong to this lead");
        } else {
            cat = new LeadBudgetEntity();
            cat.setLeadId(leadId);
            cat.setCreatedBy(userId);
        }

        cat.setSeqNo(body.getSeqNo() != null ? body.getSeqNo() : 1);
        cat.setCategory(body.getCategory().trim());
        cat.setDescription(body.getDescription());
        cat.setNotes(body.getNotes());

        LeadBudgetEntity saved = leadBudgetRepo.save(cat);
        return budgetCategoryMap(saved);
    }

    public void deleteBudgetCategory(Long leadId, Long catId, Long userId, String userRole) throws CustomException {
        authorize(leadId, userId, userRole);
        LeadBudgetEntity cat = leadBudgetRepo.findById(catId)
                .orElseThrow(() -> new CustomException("Budget category not found"));
        if (!leadId.equals(cat.getLeadId())) throw new CustomException("Budget category does not belong to this lead");

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // Soft-delete the category's items first.
        for (LeadBudgetItemEntity it : leadBudgetItemRepo.findByBudgetIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(catId)) {
            it.setDeletedAt(now);
            leadBudgetItemRepo.save(it);
        }
        cat.setDeletedAt(now);
        leadBudgetRepo.save(cat);
    }

    public Map<String, Object> saveBudgetItem(Long leadId, BudgetItemRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        if (body.getItemName() == null || body.getItemName().isBlank()) {
            throw new CustomException("itemName is required");
        }

        LeadBudgetItemEntity item;
        Long budgetId;
        if (body.getId() != null) {
            item = leadBudgetItemRepo.findById(body.getId())
                    .orElseThrow(() -> new CustomException("Budget item not found"));
            if (!leadId.equals(item.getLeadId())) throw new CustomException("Budget item does not belong to this lead");
            budgetId = item.getBudgetId();
        } else {
            budgetId = body.getBudgetId();
            if (budgetId == null) throw new CustomException("budgetId is required");
            item = new LeadBudgetItemEntity();
            item.setLeadId(leadId);
            item.setBudgetId(budgetId);
            item.setCreatedBy(userId);
        }

        // Validate the parent category belongs to this lead.
        LeadBudgetEntity cat = leadBudgetRepo.findById(budgetId)
                .orElseThrow(() -> new CustomException("Budget category not found"));
        if (!leadId.equals(cat.getLeadId())) throw new CustomException("Budget category does not belong to this lead");

        item.setSeqNo(body.getSeqNo() != null ? body.getSeqNo() : 1);
        item.setItemName(body.getItemName().trim());
        item.setMake(body.getMake());
        item.setQuantity(body.getQuantity());
        item.setUnit(body.getUnit());
        item.setUnitRate(body.getUnitRate());
        item.setNotes(body.getNotes());

        BigDecimal qty = body.getQuantity() != null ? body.getQuantity() : BigDecimal.ZERO;
        BigDecimal rate = body.getUnitRate() != null ? body.getUnitRate() : BigDecimal.ZERO;
        item.setAmount(qty.multiply(rate).setScale(2, RoundingMode.HALF_UP));

        LeadBudgetItemEntity saved = leadBudgetItemRepo.save(item);

        BigDecimal allocated = recomputeCategoryAllocated(cat);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", budgetItemMap(saved));
        data.put("allocatedAmount", allocated);
        return data;
    }

    public Map<String, Object> deleteBudgetItem(Long leadId, Long itemId, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);
        LeadBudgetItemEntity item = leadBudgetItemRepo.findById(itemId)
                .orElseThrow(() -> new CustomException("Budget item not found"));
        if (!leadId.equals(item.getLeadId())) throw new CustomException("Budget item does not belong to this lead");

        item.setDeletedAt(java.time.LocalDateTime.now());
        leadBudgetItemRepo.save(item);

        BigDecimal allocated = BigDecimal.ZERO;
        LeadBudgetEntity cat = leadBudgetRepo.findById(item.getBudgetId()).orElse(null);
        if (cat != null) allocated = recomputeCategoryAllocated(cat);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allocatedAmount", allocated);
        return data;
    }

    /** Recompute a category's allocated_amount = sum of its non-deleted item amounts. */
    private BigDecimal recomputeCategoryAllocated(LeadBudgetEntity cat) {
        BigDecimal total = BigDecimal.ZERO;
        for (LeadBudgetItemEntity it : leadBudgetItemRepo.findByBudgetIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(cat.getId())) {
            if (it.getAmount() != null) total = total.add(it.getAmount());
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        cat.setAllocatedAmount(total);
        leadBudgetRepo.save(cat);
        return total;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BOM — prepared from the scope, priced here, rolled up into the estimate
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getBom(Long leadId) {
        List<LeadBomEntity> lines = leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);

        List<Map<String, Object>> lineMaps = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LeadBomEntity l : lines) {
            lineMaps.add(bomLineMap(l));
            if (l.getAmount() != null) total = total.add(l.getAmount());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lines", lineMaps);
        data.put("totalAmount", total.setScale(2, RoundingMode.HALF_UP));
        // Resolved capacity (kW) so the tab can recompute auto-sized quantities live on reload.
        LeadsEntity lead = leadsRepo.findById(leadId).orElse(null);
        CapacityInfo cap = lead == null ? null
                : CapacityUtil.parse(lead.getCapacity(), lead.getCapacityUnit(), lead.getSubGroupName());
        data.put("capacityKw", cap != null && cap.isUsable() ? cap.scaleBase() : null);
        // The raw stored capacity too, so the tab can tell "no capacity recorded"
        // from "a capacity that doesn't parse" and can pre-fill the unit it saves back.
        data.put("capacity", lead == null ? null : lead.getCapacity());
        data.put("capacityUnit", lead == null ? null : lead.getCapacityUnit());
        data.put("templateStatus", bomTemplateStatus(lead, lines));
        return data;
    }

    /**
     * Whether this lead's saved BOM was built from the template version that is
     * live today. Each line snapshots its basis at suggestion time, so a template
     * corrected afterwards leaves the lead on the old rules with nothing to show
     * for it — this is what the BOM tab reads to say so and offer a re-run.
     */
    private Map<String, Object> bomTemplateStatus(LeadsEntity lead, List<LeadBomEntity> lines) {
        String projectType = lead == null ? null : lead.getSubGroupName();
        LeadScopeTemplateEntity tpl = projectType == null ? null
                : leadScopeTemplateRepo.findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(projectType).orElse(null);

        // The OLDEST version among the saved lines: one stale line is enough for
        // the BOM as a whole to be out of date.
        Long savedTemplateId = null;
        Integer savedVersion = null;
        for (LeadBomEntity l : lines) {
            if (l.getSourceTemplateId() == null) continue;
            if (savedVersion == null || (l.getTemplateVersion() != null && l.getTemplateVersion() < savedVersion)) {
                savedTemplateId = l.getSourceTemplateId();
                savedVersion = l.getTemplateVersion();
            }
        }

        Integer currentVersion = tpl == null ? null : (tpl.getVersion() == null ? 1 : tpl.getVersion());
        boolean outdated = tpl != null && savedTemplateId != null
                && (!savedTemplateId.equals(tpl.getId())
                    || (savedVersion != null && currentVersion != null && savedVersion < currentVersion));

        // Lines that came from a template at all, and how many of those predate
        // provenance being recorded. A BOM with no basis anywhere was hand-built
        // or imported and must never be nagged about templates.
        int basisLineCount = 0, unstampedCount = 0, unsizedCount = 0;
        List<LeadBomEntity> unstampedUnsized = new ArrayList<>();
        List<LeadBomEntity> unstamped = new ArrayList<>();
        for (LeadBomEntity l : lines) {
            if (l.getBasis() == null || l.getBasis().isBlank()) continue;
            basisLineCount++;
            boolean unsized = isUnsized(l);
            if (unsized) unsizedCount++;
            if (l.getSourceTemplateId() != null) continue;
            unstampedCount++;
            unstamped.add(l);
            if (unsized) unstampedUnsized.add(l);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasTemplate", tpl != null);
        m.put("templateName", tpl == null ? null : tpl.getName());
        m.put("currentTemplateId", tpl == null ? null : tpl.getId());
        m.put("currentTemplateVersion", currentVersion);
        m.put("savedTemplateId", savedTemplateId);
        m.put("savedTemplateVersion", savedVersion);
        m.put("outdated", outdated);
        m.put("basisLineCount", basisLineCount);
        m.put("unstampedLineCount", unstampedCount);
        m.put("unsizedLineCount", unsizedCount);

        String provenance = tpl == null ? "NONE"
                : basisLineCount == 0 ? "MANUAL"      // hand-built — never nagged
                : outdated ? "OUTDATED"                // we KNOW it's stale; say the definite thing
                : unstampedCount > 0 ? "UNKNOWN"
                : "CURRENT";
        m.put("provenance", provenance);

        // "Unknown provenance" on its own is not worth saying — most such BOMs are
        // perfectly current, and warning about all of them is crying wolf. It is
        // only worth raising when re-running would actually change something, and
        // worth raising LOUDLY only when it would fix a line that can't size today.
        String reviewHint = "NONE";
        List<Map<String, Object>> changedExamples = new ArrayList<>();
        if ("UNKNOWN".equals(provenance)) {
            List<LeadBomTemplateItemEntity> tplLines =
                    leadBomTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(tpl.getId());
            Map<Long, LeadBomTemplateItemEntity> byItemId = new LinkedHashMap<>();
            Map<String, LeadBomTemplateItemEntity> byKey = new LinkedHashMap<>();
            for (LeadBomTemplateItemEntity t : tplLines) {
                if (t.getBomItemId() != null) byItemId.putIfAbsent(t.getBomItemId(), t);
                String key = t.getMatchKey() != null && !t.getMatchKey().isBlank()
                        ? t.getMatchKey() : suggestionEngine.normalize(t.getItemName());
                byKey.putIfAbsent(key, t);
            }

            boolean changed = false, fixesUnsized = false;
            // Unsized lines first, so the example we show is one the estimator can
            // see is broken right now.
            List<LeadBomEntity> ordered = new ArrayList<>(unstampedUnsized);
            for (LeadBomEntity l : unstamped) if (!unstampedUnsized.contains(l)) ordered.add(l);

            for (LeadBomEntity l : ordered) {
                LeadBomTemplateItemEntity t = l.getBomItemId() == null ? null : byItemId.get(l.getBomItemId());
                if (t == null) t = byKey.get(suggestionEngine.normalize(l.getItemName()));
                if (t == null || !ruleDiffers(l, t)) continue; // a dropped item is a scope change, not a fix
                changed = true;
                boolean unsized = unstampedUnsized.contains(l);
                if (unsized && leadAdminService.canProduceQuantity(t)) fixesUnsized = true;
                if (changedExamples.size() < 3) {
                    Map<String, Object> ex = new LinkedHashMap<>();
                    ex.put("itemName", l.getItemName());
                    ex.put("savedBasisLabel", basisDisplayLabel(l.getBasis()));
                    ex.put("templateBasisLabel", basisDisplayLabel(t.getBasis()));
                    ex.put("unsized", unsized);
                    changedExamples.add(ex);
                }
            }
            reviewHint = fixesUnsized ? "STRONG" : changed ? "SOFT" : "NONE";
        }
        m.put("reviewHint", reviewHint);
        m.put("changedExamples", changedExamples);
        return m;
    }

    /**
     * A line that cannot produce a quantity from its OWN saved snapshot, and shows
     * nothing for it. Blank-or-zero, because lines saved before blank quantities
     * were storable hold a 0 that was never really calculated.
     *
     * Deliberately excludes missing capacity and make availability: those are the
     * lead's own inputs, they already have their own notice and per-row reason, and
     * counting them here would nag every capacity-less lead about the template.
     */
    private static boolean isUnsized(LeadBomEntity l) {
        if (Boolean.FALSE.equals(l.getAutoQty())) return false;
        BigDecimal q = l.getQuantity();
        if (q != null && q.signum() != 0) return false;
        switch (l.getBasis() == null ? "" : l.getBasis()) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:
            case LeadBomTemplateItemEntity.BASIS_PER_KW:
            case LeadBomTemplateItemEntity.BASIS_PER_MODULE:
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER:
                return !isPositive(l.getBasisValue());
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:
                return !isPositive(l.getStepValue());
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT:
                return l.getSiteVisitField() == null || l.getSiteVisitField().isBlank();
            case LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK:
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW:
                return !isPositive(l.getDriverAttr());
            default:
                return false;
        }
    }

    /** Whether the template's rule for this material has moved since the lead snapshotted it. */
    private static boolean ruleDiffers(LeadBomEntity l, LeadBomTemplateItemEntity t) {
        return !java.util.Objects.equals(l.getBasis(), t.getBasis())
                || cmp(l.getBasisValue(), t.getBasisValue()) != 0
                || cmp(l.getStepValue(), t.getStepValue()) != 0
                || !java.util.Objects.equals(blankToNull(l.getSiteVisitField()), blankToNull(t.getSiteVisitField()));
    }

    /** Null-safe numeric comparison — 1.70 and 1.7 are the same rule, not a change. */
    private static int cmp(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static boolean isPositive(BigDecimal v) { return v != null && v.signum() > 0; }

    /** Matches the labels in the template admin's basis dropdown. */
    private static String basisDisplayLabel(String basis) {
        switch (basis == null ? "" : basis) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:           return "Fixed quantity";
            case LeadBomTemplateItemEntity.BASIS_PER_KW:          return "Per kW";
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:        return "Per step";
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT: return "From site visit";
            case LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK:   return "Module count (from Wp)";
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW: return "Inverter count (from kW)";
            case LeadBomTemplateItemEntity.BASIS_PER_MODULE:      return "Per module count";
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER:    return "Per inverter count";
            default: return basis;
        }
    }

    /**
     * Set the plant capacity from the BOM tab. Capacity is what every auto-sized
     * quantity is derived from, so the tab that shows those quantities is also
     * where an estimator discovers it is missing — this lets them fix it there
     * instead of leaving to the lead form and losing their place.
     */
    public Map<String, Object> updateCapacity(Long leadId, CapacityRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        String value = body == null || body.getCapacity() == null ? null : body.getCapacity().trim();
        if (value == null || value.isBlank()) throw new CustomException("Enter a capacity");

        String unit = body.getCapacityUnit() == null || body.getCapacityUnit().isBlank()
                ? (lead.getCapacityUnit() == null || lead.getCapacityUnit().isBlank() ? "kW" : lead.getCapacityUnit())
                : body.getCapacityUnit().trim();

        CapacityInfo cap = CapacityUtil.parse(value, unit, lead.getSubGroupName());
        if (!cap.isUsable()) {
            throw new CustomException("\"" + value + " " + unit + "\" is not a capacity the BOM can size from.");
        }

        lead.setCapacity(value);
        lead.setCapacityUnit(unit);
        leadsRepo.save(lead);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capacity", value);
        data.put("capacityUnit", unit);
        data.put("capacityKw", cap.scaleBase());
        return data;
    }

    /**
     * Whole-list replace of a lead's BOM. Lines absent from the payload are
     * soft-deleted. amount is derived as quantity × unitRate unless the client
     * sends an explicit amount, so lump-sum lines (no qty/rate) still work.
     */
    public Map<String, Object> saveBom(Long leadId, BomSaveRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        List<BomLineRequest> incoming = body.getLines() != null ? body.getLines() : List.of();

        for (BomLineRequest r : incoming) {
            if (r.getItemName() == null || r.getItemName().isBlank()) {
                throw new CustomException("Every BOM line needs an item name");
            }
        }

        // Scope items the BOM is allowed to point at — anything else is stale or
        // belongs to another lead, and is stored as an unlinked line rather than
        // silently trusted.
        Set<Long> validScopeIds = new HashSet<>();
        for (LeadScopeItemEntity it : leadScopeItemRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId)) {
            validScopeIds.add(it.getId());
        }

        List<LeadBomEntity> existing = leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        Map<Long, LeadBomEntity> byId = new LinkedHashMap<>();
        for (LeadBomEntity l : existing) byId.put(l.getId(), l);

        Set<Long> keptIds = new HashSet<>();
        int seq = 1;

        for (BomLineRequest r : incoming) {
            LeadBomEntity line;
            if (r.getId() != null) {
                line = byId.get(r.getId());
                if (line == null) throw new CustomException("BOM line does not belong to this lead");
            } else {
                line = new LeadBomEntity();
                line.setLeadId(leadId);
                line.setCreatedBy(userId);
            }

            line.setSeqNo(seq++);
            line.setScopeItemId(validScopeIds.contains(r.getScopeItemId()) ? r.getScopeItemId() : null);
            line.setCategory(r.getCategory());
            line.setItemName(r.getItemName().trim());
            line.setMake(r.getMake());
            line.setSpecification(r.getSpecification());
            line.setUnit(r.getUnit());
            line.setBomItemId(r.getBomItemId());
            line.setVariantId(r.getVariantId());
            // Auto-sizing snapshot (kept so a reloaded BOM recomputes live).
            line.setBasis(r.getBasis());
            line.setBasisValue(r.getBasisValue());
            line.setStepValue(r.getStepValue());
            line.setSiteVisitField(r.getSiteVisitField());
            line.setDriverAttr(r.getDriverAttr());
            line.setAutoQty(r.getAutoQty() == null ? Boolean.TRUE : r.getAutoQty());
            line.setSourceTemplateId(r.getSourceTemplateId());
            line.setTemplateVersion(r.getTemplateVersion());

            // A null quantity is stored as null, not coerced to zero: a line that
            // could not be sized has to come back blank on reload, or the reason
            // shown at suggestion time turns into a zero that reads as an answer.
            BigDecimal qty = r.getQuantity();
            BigDecimal rate = r.getUnitRate() != null ? r.getUnitRate() : BigDecimal.ZERO;
            line.setQuantity(qty);
            line.setUnitRate(rate);
            line.setAmount(r.getAmount() != null
                    ? r.getAmount().setScale(2, RoundingMode.HALF_UP)
                    : nz(qty).multiply(rate).setScale(2, RoundingMode.HALF_UP));
            line.setNotes(r.getNotes());

            keptIds.add(leadBomRepo.save(line).getId());
        }

        LocalDateTime now = LocalDateTime.now();
        for (LeadBomEntity l : existing) {
            if (!keptIds.contains(l.getId())) {
                l.setDeletedAt(now);
                leadBomRepo.save(l);
            }
        }

        // The BOM subtotal moved, so every PERCENT extra is now stale.
        recomputeExtras(leadId);

        return getBom(leadId);
    }

    /** Sum of the lead's live BOM line amounts — the base every estimate builds on. */
    private BigDecimal bomSubtotal(Long leadId) {
        BigDecimal total = BigDecimal.ZERO;
        for (LeadBomEntity l : leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId)) {
            if (l.getAmount() != null) total = total.add(l.getAmount());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXTRA ALLOCATIONS — costs not tied to a BOM material
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getExtras(Long leadId) {
        // Recompute first: the BOM may have moved since these were last written
        // (e.g. edited through a different tab), which would leave PERCENT lines
        // showing a figure that no longer matches their own percentage.
        recomputeExtras(leadId);

        BigDecimal bom = bomSubtotal(leadId);
        List<LeadBudgetExtraEntity> lines =
                leadBudgetExtraRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);

        List<Map<String, Object>> lineMaps = new ArrayList<>();
        BigDecimal extrasTotal = BigDecimal.ZERO;
        for (LeadBudgetExtraEntity e : lines) {
            lineMaps.add(extraLineMap(e));
            if (e.getAmount() != null) extrasTotal = extrasTotal.add(e.getAmount());
        }
        extrasTotal = extrasTotal.setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lines", lineMaps);
        data.put("bomSubtotal", bom);
        data.put("extrasTotal", extrasTotal);
        data.put("totalBudget", bom.add(extrasTotal).setScale(2, RoundingMode.HALF_UP));
        return data;
    }

    /** Whole-list replace of a lead's extra allocations. */
    public Map<String, Object> saveExtras(Long leadId, ExtrasSaveRequest body, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        List<ExtraLineRequest> incoming = body.getLines() != null ? body.getLines() : List.of();

        for (ExtraLineRequest r : incoming) {
            if (r.getName() == null || r.getName().isBlank()) {
                throw new CustomException("Every extra allocation needs a name");
            }
            if (r.getBasis() != null
                    && !LeadBudgetExtraEntity.BASIS_FIXED.equalsIgnoreCase(r.getBasis())
                    && !LeadBudgetExtraEntity.BASIS_PERCENT.equalsIgnoreCase(r.getBasis())) {
                throw new CustomException("basis must be FIXED or PERCENT");
            }
        }

        List<LeadBudgetExtraEntity> existing =
                leadBudgetExtraRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        Map<Long, LeadBudgetExtraEntity> byId = new LinkedHashMap<>();
        for (LeadBudgetExtraEntity e : existing) byId.put(e.getId(), e);

        Set<Long> keptIds = new HashSet<>();
        int seq = 1;

        for (ExtraLineRequest r : incoming) {
            LeadBudgetExtraEntity extra;
            if (r.getId() != null) {
                extra = byId.get(r.getId());
                if (extra == null) throw new CustomException("Extra allocation does not belong to this lead");
            } else {
                extra = new LeadBudgetExtraEntity();
                extra.setLeadId(leadId);
                extra.setCreatedBy(userId);
            }

            extra.setSeqNo(seq++);
            extra.setName(r.getName().trim());
            extra.setBasis(LeadBudgetExtraEntity.BASIS_PERCENT.equalsIgnoreCase(r.getBasis())
                    ? LeadBudgetExtraEntity.BASIS_PERCENT
                    : LeadBudgetExtraEntity.BASIS_FIXED);
            extra.setRateValue(r.getRateValue() != null ? r.getRateValue() : BigDecimal.ZERO);
            extra.setNotes(r.getNotes());

            keptIds.add(leadBudgetExtraRepo.save(extra).getId());
        }

        LocalDateTime now = LocalDateTime.now();
        for (LeadBudgetExtraEntity e : existing) {
            if (!keptIds.contains(e.getId())) {
                e.setDeletedAt(now);
                leadBudgetExtraRepo.save(e);
            }
        }

        return getExtras(leadId);
    }

    /**
     * Refresh every extra's stored amount against the live BOM subtotal. FIXED
     * lines are their own rate; PERCENT lines are that share of the BOM. The
     * amount column is only ever a cache of this calculation.
     */
    private BigDecimal recomputeExtras(Long leadId) {
        BigDecimal bom = bomSubtotal(leadId);
        BigDecimal total = BigDecimal.ZERO;

        for (LeadBudgetExtraEntity e : leadBudgetExtraRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId)) {
            BigDecimal rate = e.getRateValue() != null ? e.getRateValue() : BigDecimal.ZERO;
            BigDecimal amount = LeadBudgetExtraEntity.BASIS_PERCENT.equalsIgnoreCase(e.getBasis())
                    ? bom.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : rate.setScale(2, RoundingMode.HALF_UP);

            if (e.getAmount() == null || e.getAmount().compareTo(amount) != 0) {
                e.setAmount(amount);
                leadBudgetExtraRepo.save(e);
            }
            total = total.add(amount);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ESTIMATION SUMMARY / SELLING PRICE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The lead's estimate. The project COST is fixed (BOM + extras); a profit
     * MARKUP percentage is added on top to get the proposal price:
     *     proposalPrice = cost × (1 + margin%/100),  profit = price − cost.
     * The markup is the negotiation lever; the cost doesn't move with it.
     * Reads the BOM, not the legacy lead_budget categories.
     */
    public Map<String, Object> getEstimationSummary(Long leadId) {
        BigDecimal bomTotal = bomSubtotal(leadId);
        BigDecimal extrasTotal = recomputeExtras(leadId);
        BigDecimal totalCost = bomTotal.add(extrasTotal).setScale(2, RoundingMode.HALF_UP);

        BigDecimal marginPercent = leadsRepo.findById(leadId)
                .map(LeadsEntity::getProposedMarginPercent)
                .orElse(null);
        if (marginPercent == null) marginPercent = BigDecimal.ZERO;

        // price = cost × (1 + margin/100); profit = price − cost
        BigDecimal factor = BigDecimal.ONE.add(marginPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        BigDecimal proposalPrice = totalCost.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        BigDecimal profitValue = proposalPrice.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> data = new LinkedHashMap<>();
        // New names (cost-first model), with the old keys kept as aliases so any
        // lingering reader still works.
        data.put("totalCost", totalCost);
        data.put("bomTotal", bomTotal);
        data.put("extrasTotal", extrasTotal);
        data.put("marginPercent", marginPercent.setScale(2, RoundingMode.HALF_UP));
        data.put("proposalPrice", proposalPrice);
        data.put("profitValue", profitValue);
        // Aliases (legacy shape)
        data.put("totalBudget", totalCost);
        data.put("sellingPrice", proposalPrice);
        data.put("marginValue", profitValue);
        return data;
    }

    /** Set the profit markup %; the proposal price is derived from it. */
    public Map<String, Object> updateMargin(Long leadId, BigDecimal marginPercent, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        lead.setProposedMarginPercent(marginPercent);
        // Keep the derived price cached on the lead for any downstream reader.
        BigDecimal cost = bomSubtotal(leadId).add(recomputeExtras(leadId));
        BigDecimal pct = marginPercent != null ? marginPercent : BigDecimal.ZERO;
        BigDecimal price = cost.multiply(BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        lead.setProposedSellingPrice(price);
        leadsRepo.save(lead);

        return getEstimationSummary(leadId);
    }

    /**
     * Set a target proposal price directly (a negotiated round number) and
     * back-compute the markup % against the fixed cost. Cost = 0 → no markup.
     */
    public Map<String, Object> updateSellingPrice(Long leadId, BigDecimal price, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        BigDecimal target = price != null ? price : BigDecimal.ZERO;
        BigDecimal cost = bomSubtotal(leadId).add(recomputeExtras(leadId));
        BigDecimal marginPercent = BigDecimal.ZERO;
        if (cost.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = target.subtract(cost).multiply(BigDecimal.valueOf(100))
                    .divide(cost, 2, RoundingMode.HALF_UP);
        }
        lead.setProposedMarginPercent(marginPercent);
        lead.setProposedSellingPrice(target);
        leadsRepo.save(lead);

        return getEstimationSummary(leadId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SUGGESTION — scope + BOM from capacity & project type
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a suggested scope + BOM for a lead. Tries mining first (nearest-
     * capacity past lead of the same project type that has a BOM), scaled via the
     * template's basis rules; falls back to expanding the template directly. The
     * returned data is pre-fill only — the caller edits and saves through the
     * existing PUT endpoints.
     *
     * @param target "scope" | "bom" | "both" (default both)
     */
    public Map<String, Object> suggestScopeAndBom(Long leadId, String target, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);

        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        String projectType = lead.getSubGroupName();
        CapacityInfo cap = CapacityUtil.parse(lead.getCapacity(), lead.getCapacityUnit(), projectType);

        boolean wantScope = !"bom".equalsIgnoreCase(target);
        boolean wantBom = !"scope".equalsIgnoreCase(target);

        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, String> siteVisit = latestSiteVisitFields(leadId);

        // Template lines for this project type (basis authority + fallback source).
        // Resolve the ACTIVE template first, then take ITS lines — querying by
        // project_type alone combines every template for the type, so a second
        // active one (or a leftover inactive one) silently doubles the suggestion.
        LeadScopeTemplateEntity activeTemplate = projectType == null ? null
                : leadScopeTemplateRepo.findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(projectType).orElse(null);
        List<LeadBomTemplateItemEntity> templateBom = activeTemplate == null ? List.of()
                : leadBomTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(activeTemplate.getId());
        List<LeadScopeTemplateItemEntity> templateScope = activeTemplate == null ? List.of()
                : leadScopeTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(activeTemplate.getId());

        // Priority: a curated TEMPLATE is the standard for the project type, so it
        // wins whenever one exists — its scope is the agreed activity list and its
        // BOM scales by capacity via the basis rules (the "kW-wise" suggestion).
        // Mining a similar past job is only the BOOTSTRAP for project types that
        // don't have a template yet.
        boolean hasTemplate = !templateBom.isEmpty() || !templateScope.isEmpty();
        MiningResult mined = (!hasTemplate && cap.isUsable())
                ? findNearestPastBom(projectType, leadId, cap) : null;

        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> scopeItems = new ArrayList<>();
        List<Map<String, Object>> bomLines = new ArrayList<>();
        String source;

        if (hasTemplate) {
            source = "TEMPLATE";
            if (wantScope) scopeItems = resolveTemplateScope(templateScope, templateBom);
            if (wantBom) {
                // Make-driven auto-sizing: resolve each driver line's default make numeric
                // attribute (module Wp / inverter kW) so the engine can size counts.
                Map<Long, BigDecimal> driverAttrs = resolveDriverAttrs(templateBom);
                bomLines = suggestionEngine.expandTemplateBom(templateBom, cap, siteVisit, driverAttrs, warnings);
                // Pick-a-make: attach each line's makes + default, additively,
                // WITHOUT touching the engine (its output is 1:1 with templateBom).
                attachVariantChoices(bomLines, templateBom);
            }
        } else if (mined != null) {
            source = "MINED";
            data.put("sourceLeadId", mined.leadId);
            data.put("sourceCapacity", displayCapacity(mined.cap));
            data.put("scaleFactor", cap.scaleBase().divide(mined.cap.scaleBase(), 3, java.math.RoundingMode.HALF_UP));

            if (wantBom) {
                bomLines = suggestionEngine.scaleMinedBom(
                        mined.bomRows, mined.cap.scaleBase(), cap.scaleBase(),
                        templateBom, mined.scopeByItemKey, siteVisit, warnings);
            }
            if (wantScope) {
                scopeItems = !mined.scopeItems.isEmpty() ? mined.scopeItems
                        : resolveTemplateScope(templateScope, templateBom);
            }
        } else {
            source = "NONE";
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("code", LeadSuggestionEngine.W_NO_TEMPLATE_NO_HISTORY);
            w.put("message", "No template exists for this project type yet, and no similar past job was found.");
            warnings.add(w);
        }

        data.put("source", source);
        data.put("hasTemplate", hasTemplate);
        data.put("projectType", projectType);
        data.put("targetCapacity", displayCapacity(cap));
        data.put("capacityKw", cap.isUsable() ? cap.scaleBase() : null); // resolved kW for live recompute
        // Stamped onto every saved line so the lead can later tell that its BOM
        // predates the template it was built from.
        data.put("templateId", activeTemplate == null ? null : activeTemplate.getId());
        data.put("templateVersion", activeTemplate == null ? null
                : (activeTemplate.getVersion() == null ? 1 : activeTemplate.getVersion()));
        data.put("scopeItems", scopeItems);
        data.put("bomLines", bomLines);
        data.put("warnings", warnings);
        return data;
    }

    /** Lightweight check the lead tabs use to decide the "Load template" button. */
    public Map<String, Object> templateInfo(Long leadId) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        String projectType = lead.getSubGroupName();
        LeadScopeTemplateEntity tpl = projectType == null ? null
                : leadScopeTemplateRepo.findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(projectType).orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectType", projectType);
        data.put("hasTemplate", tpl != null);
        data.put("templateName", tpl != null ? tpl.getName() : null);
        return data;
    }

    /**
     * Scope items for a template: the explicit scope lines if any, otherwise the
     * distinct scope activities implied by the BOM lines' {@code scope_activity}.
     * This lets a template that only defines BOM lines (each tagged to a scope
     * activity) still produce a scope suggestion — the common way templates are
     * authored, and the reason "Suggest scope" could come back empty.
     */
    private List<Map<String, Object>> resolveTemplateScope(
            List<LeadScopeTemplateItemEntity> templateScope,
            List<LeadBomTemplateItemEntity> templateBom) {
        List<Map<String, Object>> scope = suggestionEngine.expandTemplateScope(templateScope);
        if (!scope.isEmpty()) {
            // Each line's second-level breakdown, correlated by index (expandTemplateScope
            // is 1:1 and order-preserving). Mirrors ScopeTemplateExpander, which does the
            // same for the project flow — the two resolveTemplateScope copies have to stay
            // in step, so change both or neither.
            List<String> subJson = new ArrayList<>(templateScope.size());
            for (LeadScopeTemplateItemEntity s : templateScope) subJson.add(s.getSubItems());
            scopeSubItems.attachTo(scope, subJson);
            return scope;
        }

        // Derive from BOM lines, preserving first-seen order, skipping blanks/General.
        List<Map<String, Object>> derived = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LeadBomTemplateItemEntity b : templateBom) {
            String act = b.getScopeActivity();
            if (act == null || act.isBlank()) continue;
            String key = act.trim().toLowerCase();
            if (!seen.add(key)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activity", act.trim());
            m.put("specification", null);
            m.put("unit", null);
            m.put("quantity", null);
            m.put("category", b.getCategory());
            // No template record, so no breakdown. Set explicitly so the key is always
            // present and a caller never has to tell "none" from "response predates it".
            m.put("subItems", List.of());
            derived.add(m);
        }
        return derived;
    }

    /** Winner of the mining search: the nearest past lead's BOM + scope. */
    private static class MiningResult {
        Long leadId;
        CapacityInfo cap;
        List<LeadBomEntity> bomRows;
        List<Map<String, Object>> scopeItems;
        Map<String, String> scopeByItemKey; // normalized item name → scope activity
    }

    /**
     * Nearest same-family, same-project-type past lead that has a BOM. Returns null
     * when there's no usable candidate (the caller then falls to the template).
     */
    private MiningResult findNearestPastBom(String projectType, Long excludeLeadId, CapacityInfo target) {
        if (projectType == null) return null;
        var candidates = leadBomRepo.findMiningCandidates(projectType, excludeLeadId);

        Long bestLead = null;
        CapacityInfo bestCap = null;
        BigDecimal bestDist = null;
        for (var c : candidates) {
            CapacityInfo cc = CapacityUtil.parse(c.getCapacity(), c.getCapacityUnit(), projectType);
            if (!cc.isUsable() || !CapacityUtil.sameFamily(target, cc)) continue;
            BigDecimal dist = target.scaleBase().subtract(cc.scaleBase()).abs();
            // nearest wins; tie-break on the newer lead (higher id)
            if (bestDist == null || dist.compareTo(bestDist) < 0
                    || (dist.compareTo(bestDist) == 0 && c.getLeadId() > bestLead)) {
                bestDist = dist; bestLead = c.getLeadId(); bestCap = cc;
            }
        }
        if (bestLead == null) return null;

        MiningResult r = new MiningResult();
        r.leadId = bestLead;
        r.cap = bestCap;
        r.bomRows = leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(bestLead);

        // Scope items of the winner, and a map from each BOM item's normalized name
        // to the scope activity it was filed under (via scope_item_id).
        List<LeadScopeItemEntity> winnerScope =
                leadScopeItemRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(bestLead);
        Map<Long, String> activityById = new LinkedHashMap<>();
        r.scopeItems = new ArrayList<>();
        for (LeadScopeItemEntity s : winnerScope) {
            activityById.put(s.getId(), s.getActivity());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activity", s.getActivity());
            m.put("specification", s.getSpecification());
            m.put("unit", s.getUnit());
            m.put("quantity", s.getQuantity());
            m.put("category", s.getCategory());
            r.scopeItems.add(m);
        }
        r.scopeByItemKey = new LinkedHashMap<>();
        for (LeadBomEntity b : r.bomRows) {
            String act = b.getScopeItemId() != null ? activityById.get(b.getScopeItemId()) : null;
            if (act != null) r.scopeByItemKey.put(suggestionEngine.normalize(b.getItemName()), act);
        }
        return r;
    }

    /** The latest site visit's numeric-ish fields, keyed by snake_case column name. */
    private Map<String, String> latestSiteVisitFields(Long leadId) {
        Map<String, String> m = new LinkedHashMap<>();
        List<SiteVisitEntity> visits = siteVisitRepo.findByLeadIdOrderByVisitDateDescIdDesc(leadId);
        if (visits.isEmpty()) return m;
        SiteVisitEntity v = visits.get(0);
        m.put("ac_cable_length", v.getAcCableLength());
        m.put("dc_cable_length", v.getDcCableLength());
        m.put("sanctioned_load", v.getSanctionedLoad());
        m.put("shadow_free_area", v.getShadowFreeArea());
        return m;
    }

    private String displayCapacity(CapacityInfo cap) {
        if (cap == null || cap.value() == null) return null;
        return cap.value().stripTrailingZeros().toPlainString() + (cap.unit() != null ? " " + cap.unit() : "");
    }

    /** Zero-for-null, used where a blank quantity still has to roll into a total. */
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    // ─────────────────────────────────────────────────────────────────────────
    // MAPPERS (exact JSON shapes the frontend expects)
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> scopeHeaderMap(LeadScopeEntity h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("leadId", h.getLeadId());
        m.put("projectType", h.getProjectType());
        m.put("systemCapacity", h.getSystemCapacity());
        m.put("scopeOfWork", h.getScopeOfWork());
        m.put("technicalNotes", h.getTechnicalNotes());
        m.put("siteLocation", h.getSiteLocation());
        return m;
    }

    private Map<String, Object> scopeItemMap(LeadScopeItemEntity it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("seqNo", it.getSeqNo());
        m.put("activity", it.getActivity());
        m.put("category", it.getCategory());
        m.put("specification", it.getSpecification());
        m.put("quantity", it.getQuantity());
        m.put("unit", it.getUnit());
        m.put("notes", it.getNotes());
        m.put("subItems", scopeSubItems.parse(it.getSubItems()));
        return m;
    }

    private Map<String, Object> bomLineMap(LeadBomEntity l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("scopeItemId", l.getScopeItemId());
        m.put("seqNo", l.getSeqNo());
        m.put("category", l.getCategory());
        m.put("itemName", l.getItemName());
        m.put("make", l.getMake());
        m.put("specification", l.getSpecification());
        m.put("unit", l.getUnit());
        m.put("quantity", l.getQuantity());
        m.put("unitRate", l.getUnitRate());
        m.put("amount", l.getAmount());
        m.put("notes", l.getNotes());
        // Pick-a-make: chosen variant + catalog link, plus the item's active makes
        // so the constrained dropdown can repopulate on reload.
        m.put("bomItemId", l.getBomItemId());
        m.put("variantId", l.getVariantId());
        if (l.getBomItemId() != null) {
            String schemaJson = variantSchemaJson(l.getBomItemId());
            List<Map<String, Object>> variants = new ArrayList<>();
            for (BomItemVariantEntity v : bomItemVariantRepo
                    .findByBomItemIdAndIsActiveTrueOrderByMakeAsc(l.getBomItemId())) {
                variants.add(variantChoiceMap(v, schemaJson));
            }
            m.put("variants", variants);
        }
        // Auto-sizing metadata so a reloaded BOM stays live (recompute on capacity/make change).
        m.put("basis", l.getBasis());
        m.put("basisValue", l.getBasisValue());
        m.put("stepValue", l.getStepValue());
        m.put("siteVisitField", l.getSiteVisitField());
        m.put("driverAttr", l.getDriverAttr());
        m.put("autoQty", l.getAutoQty() == null ? Boolean.TRUE : l.getAutoQty());
        m.put("sourceTemplateId", l.getSourceTemplateId());
        m.put("templateVersion", l.getTemplateVersion());
        putDriverAttrNaming(m, l.getBasis(), l.getBomItemId());
        return m;
    }

    /**
     * The catalogue attribute this line's basis reads off the selected make, named
     * the way the catalogue names it. Without this a line that can't size can only
     * say "the make is missing something"; with it, it can say which something.
     */
    private void putDriverAttrNaming(Map<String, Object> line, String basis, Long bomItemId) {
        String key = VariantAttributes.attrKeyForBasis(basis);
        if (key == null) return;
        line.put("driverAttrKey", key);
        line.put("driverAttrLabel", VariantAttributes.labelForKey(variantSchemaJson(bomItemId), key));
    }

    /**
     * Attach each suggested BOM line's makes + default, correlating the engine
     * output to its template line by index (expandTemplateBom is 1:1 and
     * order-preserving). Additive only — the engine is not modified.
     *
     * The dropdown shows ALL of the item's active makes (not just the curated
     * template subset); the admin-curated make, if any, is only the pre-selected
     * default. This matches the reload path (bomLineMap) so suggest and reload agree.
     */
    private void attachVariantChoices(List<Map<String, Object>> bomLines,
                                      List<LeadBomTemplateItemEntity> templateBom) {
        int n = Math.min(bomLines.size(), templateBom.size());
        for (int i = 0; i < n; i++) {
            LeadBomTemplateItemEntity tl = templateBom.get(i);
            // Named even when nothing is attached — a driver line with no catalogue
            // item still has to be able to say what it was looking for.
            putDriverAttrNaming(bomLines.get(i), tl.getBasis(), tl.getBomItemId());
            if (tl.getBomItemId() == null || tl.getId() == null) continue;

            List<BomItemVariantEntity> active =
                    bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(tl.getBomItemId());
            if (active.isEmpty()) continue;

            String schemaJson = variantSchemaJson(tl.getBomItemId());
            List<Map<String, Object>> variants = new ArrayList<>();
            for (BomItemVariantEntity v : active) variants.add(variantChoiceMap(v, schemaJson));

            Long defId = defaultVariantId(tl, active);

            Map<String, Object> line = bomLines.get(i);
            line.put("bomItemId", tl.getBomItemId());
            line.put("variants", variants);
            line.put("defaultVariantId", defId);
            line.put("variantId", defId);
        }
    }

    private Map<String, Object> variantChoiceMap(BomItemVariantEntity v, String schemaJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", v.getId());
        m.put("make", v.getMake());
        m.put("model", v.getModel());
        m.put("description", v.getDescription());
        Map<String, Object> attrs = parseAttrs(v.getAttributeValues());
        m.put("attributeValues", attrs);            // drives make-based auto-sizing on the client
        m.put("spec", composeSpec(schemaJson, attrs)); // so picking a make refreshes the Specifications cell
        return m;
    }

    /** The raw variant-attribute schema JSON for a catalog item (null when none). */
    private String variantSchemaJson(Long bomItemId) {
        if (bomItemId == null) return null;
        return bomItemsMasterRepo.findById(bomItemId)
                .map(BomItemsMasterEntity::getVariantAttributes).orElse(null);
    }

    /**
     * Compose a variant's structured spec ("590 Wp · TOPCon · N-Type") from the item's
     * attribute schema (order + units) and the variant's stored values. Mirrors the
     * frontend specSummary in TemplateLineVariantsModal so it matches template specs.
     */
    private String composeSpec(String schemaJson, Map<String, Object> values) {
        if (schemaJson == null || schemaJson.isBlank() || values == null || values.isEmpty()) return null;
        List<Map<String, Object>> schema;
        try {
            schema = objectMapper.readValue(schemaJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> f : schema) {
            Object keyO = f.get("key");
            if (keyO == null) continue;
            Object val = values.get(String.valueOf(keyO));
            if (val == null || String.valueOf(val).isBlank()) continue;
            Object unit = f.get("unit");
            String u = unit == null ? "" : String.valueOf(unit).trim();
            parts.add(String.valueOf(val) + (u.isEmpty() ? "" : " " + u));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts); // "·" separator, matches template spec format
    }

    // ── Make-driven auto-sizing helpers ──────────────────────────────────────

    /**
     * The numeric driver each template line's default make contributes: module
     * wattage (Wp) for PER_WATT_PEAK lines, inverter capacity (kW) for
     * PER_INVERTER_KW lines. Keyed by template-line id; absent when unresolved.
     */
    private Map<Long, BigDecimal> resolveDriverAttrs(List<LeadBomTemplateItemEntity> templateBom) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (LeadBomTemplateItemEntity t : templateBom) {
            String key = attrKeyForBasis(t.getBasis());
            if (key == null || t.getBomItemId() == null || t.getId() == null) continue;
            List<BomItemVariantEntity> active =
                    bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(t.getBomItemId());
            Long defId = defaultVariantId(t, active);
            if (defId == null) continue;
            BomItemVariantEntity v = active.stream().filter(x -> x.getId().equals(defId)).findFirst().orElse(null);
            BigDecimal attr = numericAttr(v, key);
            if (attr != null) out.put(t.getId(), attr);
        }
        return out;
    }

    /** Curated default make if present and still active, else the first active make. */
    private Long defaultVariantId(LeadBomTemplateItemEntity tl, List<BomItemVariantEntity> active) {
        if (active == null || active.isEmpty()) return null;
        Long defId = null;
        for (TemplateLineVariantEntity x : templateLineVariantRepo.findByTemplateItemId(tl.getId())) {
            if (Boolean.TRUE.equals(x.getIsDefault())) { defId = x.getVariantId(); break; }
        }
        if (defId != null) {
            for (BomItemVariantEntity v : active) if (v.getId().equals(defId)) return defId;
        }
        return active.get(0).getId();
    }

    // These three delegate to VariantAttributes so the suggestion path, template
    // validation and the catalogue health check all agree on when a make carries
    // the number a basis needs.
    private String attrKeyForBasis(String basis) {
        return VariantAttributes.attrKeyForBasis(basis);
    }

    /** Parse a variant's attribute_values JSON to a flat map; null on blank/malformed. */
    private Map<String, Object> parseAttrs(String json) {
        return VariantAttributes.parseValues(json);
    }

    /** Numeric value of one attribute key on a variant (e.g. wattage/capacity); null if absent/non-numeric. */
    private BigDecimal numericAttr(BomItemVariantEntity v, String key) {
        return v == null ? null : VariantAttributes.numeric(v.getAttributeValues(), key);
    }

    private Map<String, Object> extraLineMap(LeadBudgetExtraEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("seqNo", e.getSeqNo());
        m.put("name", e.getName());
        m.put("basis", e.getBasis());
        m.put("rateValue", e.getRateValue());
        m.put("amount", e.getAmount());
        m.put("notes", e.getNotes());
        return m;
    }

    private Map<String, Object> budgetCategoryMap(LeadBudgetEntity cat) {
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (LeadBudgetItemEntity it : leadBudgetItemRepo.findByBudgetIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(cat.getId())) {
            itemMaps.add(budgetItemMap(it));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cat.getId());
        m.put("seqNo", cat.getSeqNo());
        m.put("category", cat.getCategory());
        m.put("description", cat.getDescription());
        m.put("allocatedAmount", cat.getAllocatedAmount());
        m.put("notes", cat.getNotes());
        m.put("items", itemMaps);
        return m;
    }

    private Map<String, Object> budgetItemMap(LeadBudgetItemEntity it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("budgetId", it.getBudgetId());
        m.put("seqNo", it.getSeqNo());
        m.put("itemName", it.getItemName());
        m.put("make", it.getMake());
        m.put("quantity", it.getQuantity());
        m.put("unit", it.getUnit());
        m.put("unitRate", it.getUnitRate());
        m.put("amount", it.getAmount());
        m.put("notes", it.getNotes());
        return m;
    }
}
