package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.ScopeActivitySuggestionEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBudgetItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBudgetRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.ScopeActivitySuggestionRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetCategoryRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemRequest;

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
    // ESTIMATION SUMMARY / SELLING PRICE
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getEstimationSummary(Long leadId) {
        BigDecimal totalBudget = BigDecimal.ZERO;
        for (LeadBudgetEntity cat : leadBudgetRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId)) {
            if (cat.getAllocatedAmount() != null) totalBudget = totalBudget.add(cat.getAllocatedAmount());
        }
        totalBudget = totalBudget.setScale(2, RoundingMode.HALF_UP);

        BigDecimal sellingPrice = leadsRepo.findById(leadId)
                .map(LeadsEntity::getProposedSellingPrice)
                .orElse(null);
        if (sellingPrice == null) sellingPrice = BigDecimal.ZERO;
        sellingPrice = sellingPrice.setScale(2, RoundingMode.HALF_UP);

        BigDecimal marginValue = sellingPrice.subtract(totalBudget).setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginPercent = BigDecimal.ZERO;
        if (sellingPrice.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = marginValue.multiply(BigDecimal.valueOf(100))
                    .divide(sellingPrice, 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalBudget", totalBudget);
        data.put("sellingPrice", sellingPrice);
        data.put("marginValue", marginValue);
        data.put("marginPercent", marginPercent);
        return data;
    }

    public Map<String, Object> updateSellingPrice(Long leadId, BigDecimal value, Long userId, String userRole)
            throws CustomException {
        authorize(leadId, userId, userRole);
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        lead.setProposedSellingPrice(value);
        leadsRepo.save(lead);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sellingPrice", value != null ? value : BigDecimal.ZERO);
        return data;
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
