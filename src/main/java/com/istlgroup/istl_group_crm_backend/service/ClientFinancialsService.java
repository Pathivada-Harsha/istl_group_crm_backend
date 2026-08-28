package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectExpenseRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ClientFinancialsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Money in / money out for ONE CLIENT, rolled up across every project that
 * client has. Backs the Customers -&gt; Financials tab.
 *
 * <h3>The one rule that matters</h3>
 * Every figure here comes from {@link ProjectFinancialsSummaryService}, which is
 * the same live roll-up the projects list uses and which mirrors
 * ProjectDashboardService.buildFinancialData query for query. So a project that
 * shows X on its own dashboard contributes exactly X here — the client total
 * reconciles to the rupee with the sum of its projects' dashboards because it
 * IS that sum, not a second calculation that happens to agree.
 *
 * Nothing on this path reads projects.paid_invoice_value / paid_bill_value or
 * the project-dashboard-summary table. Those only refresh when ProjectStatsService
 * runs, so they drift; the live dashboards abandoned them for that reason and
 * this screen must not resurrect them.
 *
 * <h3>Cost</h3>
 * Bounded, never one-query-per-project:
 * <pre>
 *   5  the batched roll-up (invoices, receipts, bills, advances, payments)
 *   1  the client's projects
 *   1  projects the client only reaches through an order book
 *   1  company-wide eligible project ids, for the concentration figure
 *   1  awarded value per project, from the client's order books
 *   1  approved internal expenses per project
 * </pre>
 * Ten queries whether the client has one project or fifty.
 *
 * <h3>What is counted</h3>
 * A project belongs to the client if projects.customer_id holds their customer
 * CODE, or if one of their order books points at it. Projects that are
 * deactivated or CANCELLED are excluded from both the table and the totals —
 * excluding from one but not the other would break the reconciliation the
 * breakdown table exists to prove. The count of what was dropped is returned so
 * the tab can say so out loud. Cancelled vendor bills are already excluded
 * upstream, by the same filter the project dashboard applies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClientFinancialsService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CustomersRepo                   customersRepo;
    private final ProjectRepository               projectRepository;
    private final OrderBookRepo                   orderBookRepo;
    private final ProjectExpenseRepository        expenseRepository;
    private final ProjectFinancialsSummaryService financialsSummaryService;

    public ClientFinancialsWrapper getClientFinancials(Long customerId) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
            .filter(c -> c.getDeletedAt() == null)
            .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));

        // ── 1. Which projects are this client's ──────────────────────────────
        // Two routes in, because either can be the only one present: the
        // project's own customer_id (which holds the customer CODE — see
        // DropdownProjectService.createProjectFromOrderBook), and the order book
        // that created it. The order-book roll-up doubles as the contract value.
        Map<String, BigDecimal> awardedByProject =
            toDecimalMap(orderBookRepo.sumAwardedValueByProjectForCustomer(customerId));

        List<ProjectEntity> candidates = customer.getCustomerCode() == null
            ? List.of()
            : projectRepository.findByCustomerId(customer.getCustomerCode());

        Map<String, ProjectEntity> byId = new LinkedHashMap<>();
        for (ProjectEntity p : candidates) byId.put(p.getProjectUniqueId(), p);

        Set<String> wanted = new TreeSet<>(byId.keySet());
        wanted.addAll(awardedByProject.keySet());

        // Order-book-only projects still need their name / status / budget.
        List<String> missing = wanted.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            for (ProjectEntity p : projectRepository.findAllByProjectUniqueIdIn(missing)) {
                byId.put(p.getProjectUniqueId(), p);
            }
        }

        // Live and not cancelled, or it counts nowhere.
        List<ProjectEntity> included = new ArrayList<>();
        int excluded = 0;
        for (String id : wanted) {
            ProjectEntity p = byId.get(id);
            if (p == null) continue;                  // order book points at a project that is gone
            if (isEligible(p)) included.add(p); else excluded++;
        }
        included.sort(Comparator.comparing(ProjectEntity::getProjectName,
                      Comparator.nullsLast(Comparator.naturalOrder())));

        // ── 2. The live money, for every project in the system, in 5 queries ──
        // Taken whole rather than pre-filtered, because the same map supplies
        // both this client's numbers AND the company-wide denominator below.
        // One source, so the share can never be a ratio of two different worlds.
        Map<String, ProjectFinancialsSummaryService.Financials> fin =
            financialsSummaryService.getFinancialsByProject();

        // ── 3. Approved internal expenses — budget utilisation + composition ──
        List<String> includedIds = included.stream().map(ProjectEntity::getProjectUniqueId).toList();
        Map<String, BigDecimal> expenseByProject = includedIds.isEmpty()
            ? Map.of()
            : toDecimalMap(expenseRepository.sumApprovedGroupedByProject(includedIds));

        // ── 4. Per-project rows, accumulating the totals as we go ────────────
        List<ClientFinancialsWrapper.ProjectRow> rows = new ArrayList<>();
        BigDecimal tBilled = BigDecimal.ZERO, tReceived = BigDecimal.ZERO;
        BigDecimal tPayable = BigDecimal.ZERO, tSpent = BigDecimal.ZERO;
        BigDecimal tContract = BigDecimal.ZERO;
        BigDecimal tAdvances = BigDecimal.ZERO, tBillPayments = BigDecimal.ZERO;
        BigDecimal tInternal = BigDecimal.ZERO;

        for (ProjectEntity p : included) {
            String id = p.getProjectUniqueId();
            ProjectFinancialsSummaryService.Financials f =
                fin.getOrDefault(id, ProjectFinancialsSummaryService.empty());

            // Awarded value from the order book; the project's budget is the
            // fallback for a project with no live order book row — it was seeded
            // FROM order_book.total_amount at creation, so it is the same number
            // by construction, not a different definition sneaking in.
            BigDecimal contract = awardedByProject.get(id);
            if (contract == null || contract.signum() == 0) contract = safe(p.getBudget());

            BigDecimal internal = safe(expenseByProject.get(id));
            BigDecimal projectCost = f.getPayable().add(internal);

            rows.add(ClientFinancialsWrapper.ProjectRow.builder()
                .projectUniqueId(id)
                .projectName(p.getProjectName())
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .contractValue(contract)
                .billed(f.getBilled())
                .received(f.getReceived())
                .payable(f.getPayable())
                .spent(f.getSpent())
                .outstandingReceivable(f.getPendingReceipts())
                .netCash(f.getBalance())
                .projectedMargin(f.getBilled().subtract(f.getPayable()))
                .budgetUtilisationPercent(utilisation(p.getBudget(), projectCost))
                .overBudget(isOverBudget(p.getBudget(), projectCost))
                .build());

            tBilled       = tBilled.add(f.getBilled());
            tReceived     = tReceived.add(f.getReceived());
            tPayable      = tPayable.add(f.getPayable());
            tSpent        = tSpent.add(f.getSpent());
            tContract     = tContract.add(contract);
            tAdvances     = tAdvances.add(f.getSpentViaAdvances());
            tBillPayments = tBillPayments.add(f.getSpentViaBillPayments());
            tInternal     = tInternal.add(internal);
        }

        // ── 5. Concentration — this client against the whole company ─────────
        BigDecimal companyBilled = BigDecimal.ZERO, companyReceived = BigDecimal.ZERO;
        for (String id : projectRepository.findEligibleProjectIds()) {
            ProjectFinancialsSummaryService.Financials f = fin.get(id);
            if (f == null) continue;
            companyBilled   = companyBilled.add(f.getBilled());
            companyReceived = companyReceived.add(f.getReceived());
        }

        ClientFinancialsWrapper.Totals totals = ClientFinancialsWrapper.Totals.builder()
            .billed(tBilled)
            .received(tReceived)
            .payable(tPayable)
            .spent(tSpent)
            .outstandingReceivable(tBilled.subtract(tReceived).max(BigDecimal.ZERO))
            .outstandingPayable(tPayable.subtract(tSpent).max(BigDecimal.ZERO))
            .netCash(tReceived.subtract(tSpent))              // signed on purpose
            .projectedMargin(tBilled.subtract(tPayable))      // signed on purpose
            .contractValue(tContract)
            .percentBilled(share(tBilled, tContract))
            .percentCollected(share(tReceived, tContract))
            .projectCount(rows.size())
            .build();

        boolean noActivity = tBilled.signum() == 0 && tReceived.signum() == 0
                          && tPayable.signum() == 0 && tSpent.signum() == 0
                          && tInternal.signum() == 0;

        return ClientFinancialsWrapper.builder()
            .customerId(customer.getId())
            .customerCode(customer.getCustomerCode())
            .customerName(customer.getName())
            .totals(totals)
            .projects(rows)
            .spendComposition(ClientFinancialsWrapper.SpendComposition.builder()
                .vendorAdvances(tAdvances)
                .vendorBillPayments(tBillPayments)
                .internalExpenses(tInternal)
                .totalOutflow(tSpent.add(tInternal))
                .build())
            .concentration(ClientFinancialsWrapper.Concentration.builder()
                .companyBilled(companyBilled)
                .companyReceived(companyReceived)
                .billedSharePercent(share(tBilled, companyBilled))
                .receivedSharePercent(share(tReceived, companyReceived))
                .build())
            .excludedProjectCount(excluded)
            .noActivity(noActivity)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** A project counts only while it is live and not cancelled. */
    private static boolean isEligible(ProjectEntity p) {
        if (Boolean.FALSE.equals(p.getIsActive())) return false;
        // if, not a switch: an enum switch makes javac emit a synthetic
        // switch-map class that spring-boot-devtools' restart classloader can
        // fail to load. Same reason ProjectDashboardService avoids one.
        return p.getStatus() != ProjectEntity.ProjectStatus.CANCELLED;
    }

    /**
     * cost / budget * 100 — the project dashboard's budget-utilisation gauge,
     * same numerator (vendor bills + approved internal expenses) and same
     * denominator. Null, not 0, when there is no budget: "we cannot say" and
     * "nothing spent" are different answers and the table renders them apart.
     */
    private static Double utilisation(BigDecimal budget, BigDecimal cost) {
        if (budget == null || budget.signum() <= 0) return null;
        return cost.multiply(HUNDRED).divide(budget, 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean isOverBudget(BigDecimal budget, BigDecimal cost) {
        Double u = utilisation(budget, cost);
        return u != null && u > 100.0;
    }

    /** part / whole * 100, or null when the whole is zero — never a fake 0%. */
    private static Double share(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return null;
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal safe(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** (key, amount) rows to map — same shape the batched roll-ups return. */
    private static Map<String, BigDecimal> toDecimalMap(List<Object[]> rows) {
        Map<String, BigDecimal> m = new HashMap<>();
        if (rows == null) return m;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            BigDecimal val = row[1] == null ? BigDecimal.ZERO
                : (row[1] instanceof BigDecimal ? (BigDecimal) row[1]
                                                : new BigDecimal(row[1].toString()));
            m.merge(String.valueOf(row[0]), val, BigDecimal::add);
        }
        return m;
    }
}
