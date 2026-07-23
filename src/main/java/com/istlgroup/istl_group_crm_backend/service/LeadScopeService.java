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

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
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
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
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
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetCategoryRequest;
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
    private TemplateLineVariantRepo templateLineVariantRepo;

    @Autowired
    private SiteVisitRepo siteVisitRepo;

    @Autowired
    private LeadSuggestionEngine suggestionEngine;

    @Autowired
    private LeadsRepo leadsRepo;

    @Autowired
    private RoleHierarchyService roleHierarchyService;

    @Autowired
    private LeadAccessRepo leadAccessRepo;

    @Autowired
    private ScopeActivitySuggestionRepo scopeActivitySuggestionRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // AUTH
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Allowed when the caller is level ≤ 2, is the lead's creator/assignee, or
     * holds an explicit lead_access grant. Throws otherwise.
     */
    private void authorize(Long leadId, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));

        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2) return;

        if (userId != null
                && (userId.equals(lead.getCreatedBy()) || userId.equals(lead.getAssignedTo()))) {
            return;
        }

        if (leadAccessRepo.existsByLeadIdAndUserId(leadId, userId)) return;

        throw new CustomException("You are not authorized to access this lead's estimation");
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

            BigDecimal qty = r.getQuantity() != null ? r.getQuantity() : BigDecimal.ZERO;
            BigDecimal rate = r.getUnitRate() != null ? r.getUnitRate() : BigDecimal.ZERO;
            line.setQuantity(qty);
            line.setUnitRate(rate);
            line.setAmount(r.getAmount() != null
                    ? r.getAmount().setScale(2, RoundingMode.HALF_UP)
                    : qty.multiply(rate).setScale(2, RoundingMode.HALF_UP));
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
        List<LeadBomTemplateItemEntity> templateBom = projectType == null ? List.of()
                : leadBomTemplateItemRepo.findByProjectTypeAndDeletedAtIsNullOrderBySeqNoAscIdAsc(projectType);
        List<LeadScopeTemplateItemEntity> templateScope = projectType == null ? List.of()
                : leadScopeTemplateItemRepo.findByProjectTypeAndDeletedAtIsNullOrderBySeqNoAscIdAsc(projectType);

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
                bomLines = suggestionEngine.expandTemplateBom(templateBom, cap, siteVisit, warnings);
                // Pick-a-make: attach each line's allowed makes + default, additively,
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
        if (!scope.isEmpty()) return scope;

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
            List<Map<String, Object>> variants = new ArrayList<>();
            for (BomItemVariantEntity v : bomItemVariantRepo
                    .findByBomItemIdAndIsActiveTrueOrderByMakeAsc(l.getBomItemId())) {
                variants.add(variantChoiceMap(v));
            }
            m.put("variants", variants);
        }
        return m;
    }

    /**
     * Attach each suggested BOM line's allowed makes + default, correlating the
     * engine output to its template line by index (expandTemplateBom is 1:1 and
     * order-preserving). Additive only — the engine is not modified.
     */
    private void attachVariantChoices(List<Map<String, Object>> bomLines,
                                      List<LeadBomTemplateItemEntity> templateBom) {
        int n = Math.min(bomLines.size(), templateBom.size());
        for (int i = 0; i < n; i++) {
            LeadBomTemplateItemEntity tl = templateBom.get(i);
            if (tl.getBomItemId() == null || tl.getId() == null) continue;

            List<TemplateLineVariantEntity> tlvs = templateLineVariantRepo.findByTemplateItemId(tl.getId());
            if (tlvs.isEmpty()) continue;

            Set<Long> allowed = new HashSet<>();
            Long defId = null;
            for (TemplateLineVariantEntity x : tlvs) {
                allowed.add(x.getVariantId());
                if (Boolean.TRUE.equals(x.getIsDefault())) defId = x.getVariantId();
            }

            List<Map<String, Object>> variants = new ArrayList<>();
            for (BomItemVariantEntity v : bomItemVariantRepo
                    .findByBomItemIdAndIsActiveTrueOrderByMakeAsc(tl.getBomItemId())) {
                if (allowed.contains(v.getId())) variants.add(variantChoiceMap(v));
            }
            if (variants.isEmpty()) continue;

            // Default must be an active allowed make; else fall back to the first.
            boolean defActive = false;
            for (Map<String, Object> vm : variants) {
                if (vm.get("variantId").equals(defId)) { defActive = true; break; }
            }
            if (!defActive) defId = (Long) variants.get(0).get("variantId");

            Map<String, Object> line = bomLines.get(i);
            line.put("bomItemId", tl.getBomItemId());
            line.put("variants", variants);
            line.put("defaultVariantId", defId);
            line.put("variantId", defId);
        }
    }

    private Map<String, Object> variantChoiceMap(BomItemVariantEntity v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", v.getId());
        m.put("make", v.getMake());
        m.put("model", v.getModel());
        m.put("description", v.getDescription());
        return m;
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
