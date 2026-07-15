package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBudgetEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectPhaseEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectScopeEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectProgressPeriodEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectBudgetRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectPhaseRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectProgressPeriodRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectScopeRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectItemRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BudgetLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BudgetRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.CommercialSummary;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.CommercialSummaryV2;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.FinanceLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.FinanceSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.PhaseRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.ScopeRequest;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBillingEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectCostEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectBillingRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectCostRepo;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBomEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectBomRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.SiteLocationRequest;

@Service
public class ProjectDetailService {

    @Autowired private DropdownProjectRepository projectRepo;
    @Autowired private ProjectScopeRepo   scopeRepo;
    @Autowired private ProjectPhaseRepo   phaseRepo;
    @Autowired private ProjectBudgetRepo  budgetRepo;
    @Autowired private ProjectBillingRepo billingRepo;
    @Autowired private ProjectCostRepo    costRepo;
    @Autowired private ProjectProgressPeriodRepo progressRepo;
    @Autowired private ProjectBomRepo     bomRepo;
    @Autowired private ProjectItemRepo    itemRepo;

    @PersistenceContext
    private EntityManager em;

    // Default EPC phase template — only used to seed a brand-new plan when the
    // user clicks "Suggest plan". Editable afterwards; nothing is forced.
    // Phase NAMES follow the company's own template (Site Survey, System
    // Simulation, Civil Design, Electrical Design) extended to cover the full
    // EPC lifecycle (procurement → handover). The startWeek/endWeek values are
    // sequencing PLACEHOLDERS only — a sensible default ordering, NOT a fixed
    // duration methodology. Edit freely. Same weeks for all capacities for now.
    private static final String[][] DEFAULT_EPC_PHASES = {
        // name,                  startWeek, endWeek
        {"Site Survey",           "1",  "2"},
        {"System Simulation",     "2",  "3"},
        {"Civil Design",          "3",  "4"},
        {"Electrical Design",     "4",  "5"},
        {"Procurement",           "5",  "7"},
        {"Installation",          "7",  "10"},
        {"Commissioning",         "10", "11"},
        {"Handover",              "11", "12"},
    };

    /**
     * Resolves the business key (project_unique_id) to the project row once.
     * project.getId() is the numeric PK used to key the child detail tables;
     * project.getProjectUniqueId() is the string used by the live financial
     * native queries (purchase_orders / project_expenses / invoices.project_id).
     */
    private DropdownProjectEntity requireProject(String projectUniqueId) throws CustomException {
        if (projectUniqueId == null || projectUniqueId.isBlank()) {
            throw new CustomException("Project not found");
        }
        return projectRepo.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new CustomException("Project not found: " + projectUniqueId));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TECHNICAL SCOPE
    // ═════════════════════════════════════════════════════════════════════════

    public ProjectScopeEntity getScope(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return scopeRepo.findByProjectId(project.getId()).orElse(null);
    }

    public List<ProjectPhaseEntity> getPhases(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return phaseRepo.findByProjectIdOrderBySeqNo(project.getId());
    }

    @Transactional
    public ProjectScopeEntity saveScope(String projectUniqueId, ScopeRequest req, Long userId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();

        ProjectScopeEntity scope = scopeRepo.findByProjectId(projectId)
            .orElseGet(() -> {
                ProjectScopeEntity s = new ProjectScopeEntity();
                s.setProjectId(projectId);
                s.setCreatedBy(userId);
                return s;
            });

        scope.setProjectType(req.getProjectType());
        scope.setScopeOfWork(req.getScopeOfWork());
        scope.setTechnicalNotes(req.getTechnicalNotes());
        scope.setSystemCapacity(req.getSystemCapacity());
        scope.setSiteLocation(req.getSiteLocation());
        scope.setSiteLat(req.getSiteLat());
        scope.setSiteLng(req.getSiteLng());
        scope.setPlannedStartDate(req.getPlannedStartDate());
        scope.setPlannedEndDate(req.getPlannedEndDate());
        scope.setTotalPlannedWeeks(req.getTotalPlannedWeeks());
        scope.setPlanUnit(req.getPlanUnit() != null ? req.getPlanUnit() : "WEEK");
        scope.setTrackingMode(req.getTrackingMode() != null ? req.getTrackingMode() : (scope.getTrackingMode() != null ? scope.getTrackingMode() : "SIMPLE"));
        scope = scopeRepo.save(scope);

        // Replace-all strategy for phases (mirrors how project_items are saved).
        if (req.getPhases() != null) {
            phaseRepo.deleteByProjectId(projectId);
            em.flush();
            int seq = 1;
            for (PhaseRequest p : req.getPhases()) {
                ProjectPhaseEntity ph = new ProjectPhaseEntity();
                ph.setProjectId(projectId);
                ph.setSeqNo(p.getSeqNo() != null ? p.getSeqNo() : seq);
                ph.setPhaseName(p.getPhaseName());
                ph.setPhaseDescription(p.getPhaseDescription());
                ph.setStartWeek(p.getStartWeek());
                ph.setEndWeek(p.getEndWeek());
                ph.setPlannedStartDate(p.getPlannedStartDate());
                ph.setPlannedEndDate(p.getPlannedEndDate());
                ph.setActualStartDate(p.getActualStartDate());
                ph.setActualEndDate(p.getActualEndDate());
                ph.setStatus(p.getStatus() != null ? p.getStatus() : "Not Started");
                ph.setProgressPercent(p.getProgressPercent() != null ? p.getProgressPercent() : BigDecimal.ZERO);
                ph.setWeightPct(p.getWeightPct());
                ph.setPlannedBudget(p.getPlannedBudget());
                ph.setResponsibleUserId(p.getResponsibleUserId());
                // Serialise sub-items list → JSON string for storage
                if (p.getSubItems() != null && !p.getSubItems().isEmpty()) {
                    try {
                        ph.setSubItems(new ObjectMapper().writeValueAsString(p.getSubItems()));
                    } catch (Exception ex) {
                        ph.setSubItems(null);
                    }
                } else {
                    ph.setSubItems(null);
                }
                phaseRepo.save(ph);
                seq++;
            }
        }
        return scope;
    }

    /**
     * Surgical budget update: sets ONLY plannedBudget on existing phases and the
     * "plannedBudget" field inside each sub-item's JSON. Does not create, delete, or
     * reorder phases, and leaves every other phase/scope field untouched — so saving
     * budgets from the Commercial tab can never clobber the Technical Scope.
     * Input: list of { phaseId, plannedBudget, subBudgets: [{ name, plannedBudget }] }.
     */
    @Transactional
    public void saveScopeBudgets(String projectUniqueId, List<java.util.Map<String, Object>> items) throws CustomException {
        if (items == null) return;
        DropdownProjectEntity project = requireProject(projectUniqueId);
        List<ProjectPhaseEntity> phases = phaseRepo.findByProjectIdOrderBySeqNo(project.getId());
        java.util.Map<Long, ProjectPhaseEntity> byId = new java.util.HashMap<>();
        for (ProjectPhaseEntity p : phases) byId.put(p.getId(), p);
        ObjectMapper mapper = new ObjectMapper();

        for (java.util.Map<String, Object> item : items) {
            Object pidRaw = item.get("phaseId");
            if (pidRaw == null) continue;
            Long pid = Long.valueOf(String.valueOf(pidRaw).replaceAll("\\..*$", ""));
            ProjectPhaseEntity ph = byId.get(pid);
            if (ph == null) continue;

            // Sub-item budgets: merge plannedBudget into the stored sub_items JSON by name.
            Object subBudgetsRaw = item.get("subBudgets");
            if (subBudgetsRaw instanceof List && ph.getSubItems() != null && !ph.getSubItems().isBlank()) {
                try {
                    List<java.util.Map<String, Object>> stored =
                        mapper.readValue(ph.getSubItems(), List.class);
                    List<?> incoming = (List<?>) subBudgetsRaw;
                    java.util.Map<String, Object> budgetByName = new java.util.HashMap<>();
                    for (Object o : incoming) {
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                        if (m.get("name") != null)
                            budgetByName.put(String.valueOf(m.get("name")), m.get("plannedBudget"));
                    }
                    java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                    for (java.util.Map<String, Object> si : stored) {
                        String nm = String.valueOf(si.get("name"));
                        if (budgetByName.containsKey(nm)) {
                            Object bv = budgetByName.get(nm);
                            si.put("plannedBudget", bv);
                            if (bv != null && !String.valueOf(bv).isBlank())
                                sum = sum.add(new java.math.BigDecimal(String.valueOf(bv)));
                        } else if (si.get("plannedBudget") != null && !String.valueOf(si.get("plannedBudget")).isBlank()) {
                            sum = sum.add(new java.math.BigDecimal(String.valueOf(si.get("plannedBudget"))));
                        }
                    }
                    ph.setSubItems(mapper.writeValueAsString(stored));
                    ph.setPlannedBudget(sum); // parent = sum of sub-item budgets (rollup)
                } catch (Exception ignored) { /* leave as-is on parse failure */ }
            } else {
                // Leaf phase (no sub-items): set its own planned budget.
                Object bv = item.get("plannedBudget");
                ph.setPlannedBudget(bv == null || String.valueOf(bv).isBlank()
                    ? null : new java.math.BigDecimal(String.valueOf(bv)));
            }
            phaseRepo.save(ph);
        }
    }

    // ── Per-period progress (DETAILED tracking) ────────────────────────────────
    public List<ProjectProgressPeriodEntity> getProgressPeriods(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return progressRepo.findByProjectId(project.getId());
    }

    /**
     * Replace-all save of per-period progress cells for a project. Only leaf cells
     * are stored (phase with sub_item_key=null, or sub-items by name). Parent curves are
     * derived at read time, never stored. Zero-valued cells are skipped to keep the table lean.
     */
    @Transactional
    public void saveProgressPeriods(String projectUniqueId, List<java.util.Map<String, Object>> cells) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        progressRepo.deleteByProjectId(projectId);
        em.flush();
        if (cells == null) return;
        for (java.util.Map<String, Object> c : cells) {
            Object pidRaw = c.get("phaseId");
            Object perRaw = c.get("periodNo");
            if (pidRaw == null || perRaw == null) continue;
            java.math.BigDecimal planned = toBD(c.get("plannedPct"));
            java.math.BigDecimal actual  = toBD(c.get("actualPct"));
            if (planned.signum() == 0 && actual.signum() == 0) continue; // skip empties
            ProjectProgressPeriodEntity e = new ProjectProgressPeriodEntity();
            e.setProjectId(projectId);
            e.setPhaseId(Long.valueOf(String.valueOf(pidRaw).replaceAll("\\..*$", "")));
            Object sk = c.get("subItemKey");
            e.setSubItemKey(sk == null || String.valueOf(sk).isBlank() ? null : String.valueOf(sk));
            e.setPeriodNo(Integer.valueOf(String.valueOf(perRaw).replaceAll("\\..*$", "")));
            e.setPlannedPct(planned);
            e.setActualPct(actual);
            progressRepo.save(e);
        }
    }

    private java.math.BigDecimal toBD(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(String.valueOf(o)); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    public List<ProjectPhaseEntity> defaultEpcPlan(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        List<ProjectPhaseEntity> out = new ArrayList<>();
        int seq = 1;
        for (String[] row : DEFAULT_EPC_PHASES) {
            ProjectPhaseEntity p = new ProjectPhaseEntity();
            p.setProjectId(projectId);
            p.setSeqNo(seq++);
            p.setPhaseName(row[0]);
            p.setStartWeek(Integer.parseInt(row[1]));
            p.setEndWeek(Integer.parseInt(row[2]));
            p.setStatus("Not Started");
            p.setProgressPercent(BigDecimal.ZERO);
            out.add(p);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  COMMERCIAL — budget allocation + live actuals
    // ═════════════════════════════════════════════════════════════════════════

    public List<ProjectBudgetEntity> getBudgetLines(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return budgetRepo.findByProjectIdOrderBySeqNo(project.getId());
    }

    @Transactional
    public List<ProjectBudgetEntity> saveBudget(String projectUniqueId, BudgetRequest req, Long userId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        budgetRepo.deleteByProjectId(projectId);
        em.flush();
        List<ProjectBudgetEntity> saved = new ArrayList<>();
        int seq = 1;
        if (req.getLines() != null) {
            for (BudgetLineRequest l : req.getLines()) {
                ProjectBudgetEntity b = new ProjectBudgetEntity();
                b.setProjectId(projectId);
                b.setSeqNo(l.getSeqNo() != null ? l.getSeqNo() : seq);
                b.setCategory(l.getCategory());
                b.setDescription(l.getDescription());
                b.setAllocatedAmount(l.getAllocatedAmount() != null ? l.getAllocatedAmount() : BigDecimal.ZERO);
                b.setNotes(l.getNotes());
                b.setCreatedBy(userId);
                saved.add(budgetRepo.save(b));
                seq++;
            }
        }
        return saved;
    }

    /**
     * Commercial summary: allocated total (from budget lines) vs live actuals
     * (procurement = sum of PO total_value; spend = sum of project_expenses
     * total_amount) keyed by the project's project_unique_id.
     */
    public CommercialSummary getCommercialSummary(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        CommercialSummary s = new CommercialSummary();
        s.setProjectId(project.getProjectUniqueId());
        s.setOrderValue(project.getBudget() != null ? project.getBudget() : BigDecimal.ZERO);

        List<ProjectBudgetEntity> lines = budgetRepo.findByProjectIdOrderBySeqNo(project.getId());
        BigDecimal allocated = lines.stream()
            .map(b -> b.getAllocatedAmount() != null ? b.getAllocatedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        s.setTotalAllocated(allocated);

        List<BudgetLineRequest> echoed = new ArrayList<>();
        for (ProjectBudgetEntity b : lines) {
            BudgetLineRequest l = new BudgetLineRequest();
            l.setId(b.getId());
            l.setSeqNo(b.getSeqNo());
            l.setCategory(b.getCategory());
            l.setDescription(b.getDescription());
            l.setAllocatedAmount(b.getAllocatedAmount());
            l.setNotes(b.getNotes());
            echoed.add(l);
        }
        s.setLines(echoed);

        String projectId = project.getProjectUniqueId();
        if (projectId == null || projectId.isBlank()) {
            s.setProjectLinked(false);
            s.setTotalProcurement(BigDecimal.ZERO);
            s.setTotalSpend(BigDecimal.ZERO);
            return s;
        }
        s.setProjectLinked(true);

        // Live procurement total (purchase_orders.total_value), non-deleted.
        Object poSum = em.createNativeQuery(
                "SELECT COALESCE(SUM(total_value),0) FROM purchase_orders " +
                "WHERE project_id = :pid AND deleted_at IS NULL")
            .setParameter("pid", projectId)
            .getSingleResult();
        s.setTotalProcurement(toBig(poSum));

        // Live spend total (project_expenses.total_amount), non-deleted.
        Object expSum = em.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount),0) FROM project_expenses " +
                "WHERE project_id = :pid AND is_deleted = 0")
            .setParameter("pid", projectId)
            .getSingleResult();
        s.setTotalSpend(toBig(expSum));

        return s;
    }

    private BigDecimal toBig(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  COMMERCIAL v2 — item-keyed billing (receivables) + cost (payables)
    // ═════════════════════════════════════════════════════════════════════════

    public List<ProjectBillingEntity> getBilling(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return billingRepo.findByProjectIdOrderBySeqNo(project.getId());
    }

    public List<ProjectCostEntity> getCost(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return costRepo.findByProjectIdOrderBySeqNo(project.getId());
    }

    @Transactional
    public void saveBilling(String projectUniqueId, FinanceSaveRequest req, Long userId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        billingRepo.deleteByProjectId(projectId);
        em.flush();
        int seq = 1;
        if (req.getLines() != null) {
            for (FinanceLineRequest l : req.getLines()) {
                ProjectBillingEntity b = new ProjectBillingEntity();
                b.setProjectId(projectId);
                b.setSeqNo(l.getSeqNo() != null ? l.getSeqNo() : seq);
                b.setItemName(l.getItemName());
                b.setAmount(l.getAmount() != null ? l.getAmount() : BigDecimal.ZERO);
                b.setPlannedDate(l.getPlannedDate());
                b.setNotes(l.getNotes());
                b.setCreatedBy(userId);
                billingRepo.save(b);
                seq++;
            }
        }
    }

    @Transactional
    public void saveCost(String projectUniqueId, FinanceSaveRequest req, Long userId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        costRepo.deleteByProjectId(projectId);
        em.flush();
        int seq = 1;
        if (req.getLines() != null) {
            for (FinanceLineRequest l : req.getLines()) {
                ProjectCostEntity c = new ProjectCostEntity();
                c.setProjectId(projectId);
                c.setSeqNo(l.getSeqNo() != null ? l.getSeqNo() : seq);
                c.setItemName(l.getItemName());
                c.setAmount(l.getAmount() != null ? l.getAmount() : BigDecimal.ZERO);
                c.setPlannedDate(l.getPlannedDate());
                c.setNotes(l.getNotes());
                c.setCreatedBy(userId);
                costRepo.save(c);
                seq++;
            }
        }
    }

    private List<FinanceLineRequest> echoBilling(List<ProjectBillingEntity> list) {
        List<FinanceLineRequest> out = new ArrayList<>();
        for (ProjectBillingEntity b : list) {
            FinanceLineRequest l = new FinanceLineRequest();
            l.setId(b.getId()); l.setSeqNo(b.getSeqNo()); l.setItemName(b.getItemName());
            l.setAmount(b.getAmount()); l.setPlannedDate(b.getPlannedDate()); l.setNotes(b.getNotes());
            out.add(l);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SITE LOCATION — editable from Overview, persisted on the scope row.
    //  Updates only the three site_* columns; never touches scope text or phases.
    //  Creates the scope row on first save if it does not yet exist.
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public ProjectScopeEntity saveSiteLocation(String projectUniqueId, SiteLocationRequest req, Long userId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        ProjectScopeEntity scope = scopeRepo.findByProjectId(projectId)
            .orElseGet(() -> {
                ProjectScopeEntity s = new ProjectScopeEntity();
                s.setProjectId(projectId);
                s.setCreatedBy(userId);
                return s;
            });
        scope.setSiteLocation(req.getSiteLocation());
        scope.setSiteLat(req.getSiteLat());
        scope.setSiteLng(req.getSiteLng());
        return scopeRepo.save(scope);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  BOM / BOQ — material lines per project
    // ═════════════════════════════════════════════════════════════════════════

    public List<ProjectBomEntity> getBom(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return bomRepo.findByProjectIdOrderBySeqNo(project.getId());
    }

    @Transactional
    public void saveBom(String projectUniqueId, BomSaveRequest req, Long userId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        Long projectId = project.getId();
        bomRepo.deleteByProjectId(projectId);
        em.flush();
        int seq = 1;
        if (req.getLines() != null) {
            for (BomLineRequest l : req.getLines()) {
                ProjectBomEntity b = new ProjectBomEntity();
                b.setProjectId(projectId);
                b.setSeqNo(l.getSeqNo() != null ? l.getSeqNo() : seq);
                b.setCategory(l.getCategory());
                b.setItemName(l.getItemName());
                b.setMake(l.getMake());
                b.setUnit(l.getUnit());
                BigDecimal qty  = l.getQuantity() != null ? l.getQuantity() : BigDecimal.ZERO;
                BigDecimal rate = l.getUnitRate() != null ? l.getUnitRate() : BigDecimal.ZERO;
                b.setQuantity(qty);
                b.setUnitRate(rate);
                // Trust an explicit amount if sent; otherwise derive qty * rate.
                b.setAmount(l.getAmount() != null ? l.getAmount() : qty.multiply(rate));
                b.setNotes(l.getNotes());
                b.setCreatedBy(userId);
                bomRepo.save(b);
                seq++;
            }
        }
    }

    private List<FinanceLineRequest> echoCost(List<ProjectCostEntity> list) {
        List<FinanceLineRequest> out = new ArrayList<>();
        for (ProjectCostEntity c : list) {
            FinanceLineRequest l = new FinanceLineRequest();
            l.setId(c.getId()); l.setSeqNo(c.getSeqNo()); l.setItemName(c.getItemName());
            l.setAmount(c.getAmount()); l.setPlannedDate(c.getPlannedDate()); l.setNotes(c.getNotes());
            out.add(l);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ITEMS — order-line items per project
    // ═════════════════════════════════════════════════════════════════════════

    public List<ProjectItemEntity> getItems(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        return itemRepo.findByProjectIdOrderByLineNo(project.getId());
    }

    public CommercialSummaryV2 getCommercialSummaryV2(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = requireProject(projectUniqueId);
        CommercialSummaryV2 s = new CommercialSummaryV2();
        s.setProjectId(project.getProjectUniqueId());
        s.setOrderValue(project.getBudget() != null ? project.getBudget() : BigDecimal.ZERO);

        List<ProjectBillingEntity> billing = billingRepo.findByProjectIdOrderBySeqNo(project.getId());
        List<ProjectCostEntity>    cost    = costRepo.findByProjectIdOrderBySeqNo(project.getId());
        s.setBillingLines(echoBilling(billing));
        s.setCostLines(echoCost(cost));
        s.setTotalBillingPlanned(billing.stream().map(b -> b.getAmount() != null ? b.getAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add));
        s.setTotalCostPlanned(cost.stream().map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add));

        String projectId = project.getProjectUniqueId();
        if (projectId == null || projectId.isBlank()) {
            s.setProjectLinked(false);
            s.setTotalInvoiced(BigDecimal.ZERO); s.setTotalPaid(BigDecimal.ZERO);
            s.setTotalInvoiceBalance(BigDecimal.ZERO);
            s.setTotalProcurement(BigDecimal.ZERO); s.setTotalSpend(BigDecimal.ZERO);
            return s;
        }
        s.setProjectLinked(true);

        // Invoiced + paid, live from invoices (non-deleted) for this project.
        Object[] inv = (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount),0), COALESCE(SUM(paid_amount),0) " +
                "FROM invoices WHERE project_id = :pid AND deleted_at IS NULL")
            .setParameter("pid", projectId).getSingleResult();
        BigDecimal invoiced = toBig(inv[0]); BigDecimal paid = toBig(inv[1]);
        s.setTotalInvoiced(invoiced); s.setTotalPaid(paid);
        s.setTotalInvoiceBalance(invoiced.subtract(paid));

        Object poSum = em.createNativeQuery(
                "SELECT COALESCE(SUM(total_value),0) FROM purchase_orders WHERE project_id = :pid AND deleted_at IS NULL")
            .setParameter("pid", projectId).getSingleResult();
        s.setTotalProcurement(toBig(poSum));

        Object expSum = em.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount),0) FROM project_expenses WHERE project_id = :pid AND is_deleted = 0")
            .setParameter("pid", projectId).getSingleResult();
        s.setTotalSpend(toBig(expSum));

        return s;
    }
}
