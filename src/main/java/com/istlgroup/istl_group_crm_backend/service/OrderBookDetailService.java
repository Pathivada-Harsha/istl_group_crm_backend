package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookBudgetEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookPhaseEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookScopeEntity;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookBudgetRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookPhaseRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookScopeRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.BudgetLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.BudgetRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.CommercialSummary;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.CommercialSummaryV2;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.FinanceLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.FinanceSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.PhaseRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.ScopeRequest;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookBillingEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookCostEntity;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookBillingRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookCostRepo;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookBomEntity;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookBomRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.SiteLocationRequest;

@Service
public class OrderBookDetailService {

    @Autowired private OrderBookRepo        orderBookRepo;
    @Autowired private OrderBookScopeRepo   scopeRepo;
    @Autowired private OrderBookPhaseRepo   phaseRepo;
    @Autowired private OrderBookBudgetRepo  budgetRepo;
    @Autowired private OrderBookBillingRepo billingRepo;
    @Autowired private OrderBookCostRepo    costRepo;
    @Autowired private OrderBookBomRepo     bomRepo;

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

    private OrderBookEntity requireOrderBook(Long orderBookId) throws CustomException {
        OrderBookEntity ob = orderBookRepo.findById(orderBookId).orElse(null);
        if (ob == null || ob.getDeletedAt() != null) {
            throw new CustomException("Order book not found");
        }
        return ob;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TECHNICAL SCOPE
    // ═════════════════════════════════════════════════════════════════════════

    public OrderBookScopeEntity getScope(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        return scopeRepo.findByOrderBookId(orderBookId).orElse(null);
    }

    public List<OrderBookPhaseEntity> getPhases(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        return phaseRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
    }

    @Transactional
    public OrderBookScopeEntity saveScope(Long orderBookId, ScopeRequest req, Long userId) throws CustomException {
        OrderBookEntity ob = requireOrderBook(orderBookId);

        OrderBookScopeEntity scope = scopeRepo.findByOrderBookId(orderBookId)
            .orElseGet(() -> {
                OrderBookScopeEntity s = new OrderBookScopeEntity();
                s.setOrderBookId(orderBookId);
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
        scope = scopeRepo.save(scope);

        // Replace-all strategy for phases (mirrors how order_book_items are saved).
        if (req.getPhases() != null) {
            phaseRepo.deleteByOrderBookId(orderBookId);
            em.flush();
            int seq = 1;
            for (PhaseRequest p : req.getPhases()) {
                OrderBookPhaseEntity ph = new OrderBookPhaseEntity();
                ph.setOrderBookId(orderBookId);
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
                ph.setResponsibleUserId(p.getResponsibleUserId());
                phaseRepo.save(ph);
                seq++;
            }
        }
        return scope;
    }

    /** Seed a default EPC plan; does NOT persist — returns it for the UI to edit then save. */
    public List<OrderBookPhaseEntity> defaultEpcPlan(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        List<OrderBookPhaseEntity> out = new ArrayList<>();
        int seq = 1;
        for (String[] row : DEFAULT_EPC_PHASES) {
            OrderBookPhaseEntity p = new OrderBookPhaseEntity();
            p.setOrderBookId(orderBookId);
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

    public List<OrderBookBudgetEntity> getBudgetLines(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        return budgetRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
    }

    @Transactional
    public List<OrderBookBudgetEntity> saveBudget(Long orderBookId, BudgetRequest req, Long userId) throws CustomException {
        requireOrderBook(orderBookId);
        budgetRepo.deleteByOrderBookId(orderBookId);
        em.flush();
        List<OrderBookBudgetEntity> saved = new ArrayList<>();
        int seq = 1;
        if (req.getLines() != null) {
            for (BudgetLineRequest l : req.getLines()) {
                OrderBookBudgetEntity b = new OrderBookBudgetEntity();
                b.setOrderBookId(orderBookId);
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
     * total_amount) keyed by the order book's project_id.
     * Gracefully returns projectLinked=false when no project is linked.
     */
    public CommercialSummary getCommercialSummary(Long orderBookId) throws CustomException {
        OrderBookEntity ob = requireOrderBook(orderBookId);
        CommercialSummary s = new CommercialSummary();
        s.setProjectId(ob.getProjectId());
        s.setOrderValue(ob.getTotalAmount() != null ? ob.getTotalAmount() : BigDecimal.ZERO);

        List<OrderBookBudgetEntity> lines = budgetRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
        BigDecimal allocated = lines.stream()
            .map(b -> b.getAllocatedAmount() != null ? b.getAllocatedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        s.setTotalAllocated(allocated);

        List<BudgetLineRequest> echoed = new ArrayList<>();
        for (OrderBookBudgetEntity b : lines) {
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

        String projectId = ob.getProjectId();
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

    public List<OrderBookBillingEntity> getBilling(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        return billingRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
    }

    public List<OrderBookCostEntity> getCost(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        return costRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
    }

    @Transactional
    public void saveBilling(Long orderBookId, FinanceSaveRequest req, Long userId) throws CustomException {
        requireOrderBook(orderBookId);
        billingRepo.deleteByOrderBookId(orderBookId);
        em.flush();
        int seq = 1;
        if (req.getLines() != null) {
            for (FinanceLineRequest l : req.getLines()) {
                OrderBookBillingEntity b = new OrderBookBillingEntity();
                b.setOrderBookId(orderBookId);
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
    public void saveCost(Long orderBookId, FinanceSaveRequest req, Long userId) throws CustomException {
        requireOrderBook(orderBookId);
        costRepo.deleteByOrderBookId(orderBookId);
        em.flush();
        int seq = 1;
        if (req.getLines() != null) {
            for (FinanceLineRequest l : req.getLines()) {
                OrderBookCostEntity c = new OrderBookCostEntity();
                c.setOrderBookId(orderBookId);
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

    private List<FinanceLineRequest> echoBilling(List<OrderBookBillingEntity> list) {
        List<FinanceLineRequest> out = new ArrayList<>();
        for (OrderBookBillingEntity b : list) {
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
    public OrderBookScopeEntity saveSiteLocation(Long orderBookId, SiteLocationRequest req, Long userId) throws CustomException {
        requireOrderBook(orderBookId);
        OrderBookScopeEntity scope = scopeRepo.findByOrderBookId(orderBookId)
            .orElseGet(() -> {
                OrderBookScopeEntity s = new OrderBookScopeEntity();
                s.setOrderBookId(orderBookId);
                s.setCreatedBy(userId);
                return s;
            });
        scope.setSiteLocation(req.getSiteLocation());
        scope.setSiteLat(req.getSiteLat());
        scope.setSiteLng(req.getSiteLng());
        return scopeRepo.save(scope);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  BOM / BOQ — material lines per order book
    // ═════════════════════════════════════════════════════════════════════════

    public List<OrderBookBomEntity> getBom(Long orderBookId) throws CustomException {
        requireOrderBook(orderBookId);
        return bomRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
    }

    @Transactional
    public void saveBom(Long orderBookId, BomSaveRequest req, Long userId) throws CustomException {
        requireOrderBook(orderBookId);
        bomRepo.deleteByOrderBookId(orderBookId);
        em.flush();
        int seq = 1;
        if (req.getLines() != null) {
            for (BomLineRequest l : req.getLines()) {
                OrderBookBomEntity b = new OrderBookBomEntity();
                b.setOrderBookId(orderBookId);
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

    private List<FinanceLineRequest> echoCost(List<OrderBookCostEntity> list) {
        List<FinanceLineRequest> out = new ArrayList<>();
        for (OrderBookCostEntity c : list) {
            FinanceLineRequest l = new FinanceLineRequest();
            l.setId(c.getId()); l.setSeqNo(c.getSeqNo()); l.setItemName(c.getItemName());
            l.setAmount(c.getAmount()); l.setPlannedDate(c.getPlannedDate()); l.setNotes(c.getNotes());
            out.add(l);
        }
        return out;
    }

    public CommercialSummaryV2 getCommercialSummaryV2(Long orderBookId) throws CustomException {
        OrderBookEntity ob = requireOrderBook(orderBookId);
        CommercialSummaryV2 s = new CommercialSummaryV2();
        s.setProjectId(ob.getProjectId());
        s.setOrderValue(ob.getTotalAmount() != null ? ob.getTotalAmount() : BigDecimal.ZERO);

        List<OrderBookBillingEntity> billing = billingRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
        List<OrderBookCostEntity>    cost    = costRepo.findByOrderBookIdOrderBySeqNo(orderBookId);
        s.setBillingLines(echoBilling(billing));
        s.setCostLines(echoCost(cost));
        s.setTotalBillingPlanned(billing.stream().map(b -> b.getAmount() != null ? b.getAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add));
        s.setTotalCostPlanned(cost.stream().map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add));

        String projectId = ob.getProjectId();
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