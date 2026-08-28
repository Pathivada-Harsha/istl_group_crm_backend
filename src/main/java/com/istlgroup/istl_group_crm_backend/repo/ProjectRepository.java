package com.istlgroup.istl_group_crm_backend.repo;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

	/**
     * Find project by unique ID
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.projectUniqueId = :projectUniqueId")
    Optional<ProjectEntity> findByProjectUniqueId(@Param("projectUniqueId") String projectUniqueId);
    
    List<ProjectEntity> findByGroupId(String groupId);
    
    List<ProjectEntity> findBySubGroupId(Long subGroupId);
    
    List<ProjectEntity> findByIsActive(Boolean isActive);

    // Dashboard Statistics Queries
    
    /**
     * Get total projects count
     */
    @Query("SELECT COUNT(p) FROM ProjectEntity p WHERE p.isActive = true")
    Long countActiveProjects();

    /**
     * Get total budget across all projects
     */
    @Query("SELECT COALESCE(SUM(p.budget), 0) FROM ProjectEntity p WHERE p.isActive = true")
    BigDecimal sumTotalBudget();

    /**
     * Get total budget utilized
     */
    @Query("SELECT COALESCE(SUM(p.budgetUtilized), 0) FROM ProjectEntity p WHERE p.isActive = true")
    BigDecimal sumTotalBudgetUtilized();

    /**
     * Get projects with high budget utilization (>80%)
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true AND p.budgetUtilizationPercent > 80 ORDER BY p.budgetUtilizationPercent DESC")
    List<ProjectEntity> findHighBudgetUtilizationProjects();

    /**
     * Get projects behind schedule
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true AND p.endDate < CURRENT_DATE AND p.status != 'COMPLETED'")
    List<ProjectEntity> findProjectsBehindSchedule();

    /**
     * Get projects by status
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true AND p.status = :status")
    List<ProjectEntity> findByStatus(@Param("status") ProjectEntity.ProjectStatus status);

    /**
     * Get top projects by spending
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true ORDER BY p.budgetUtilized DESC")
    List<ProjectEntity> findTopProjectsBySpending();

    /**
     * Get projects with pending payments
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true AND p.pendingPaymentValue > 0 ORDER BY p.pendingPaymentValue DESC")
    List<ProjectEntity> findProjectsWithPendingPayments();

    /**
     * Get total pending payments across all projects
     */
    @Query("SELECT COALESCE(SUM(p.pendingPaymentValue), 0) FROM ProjectEntity p WHERE p.isActive = true")
    BigDecimal sumTotalPendingPayments();

    /**
     * Get projects with undelivered POs
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true AND p.pendingPoValue > 0 ORDER BY p.pendingPoValue DESC")
    List<ProjectEntity> findProjectsWithPendingDeliveries();

    /**
     * Find projects by group and subgroup
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true " +
           "AND (:groupId IS NULL OR p.groupId = :groupId) " +
           "AND (:subGroupId IS NULL OR p.subGroupId = :subGroupId)")
    List<ProjectEntity> findByGroupAndSubGroup(
        @Param("groupId") String groupId,
        @Param("subGroupId") Long subGroupId
    );

    /**
     * Get project summary for dashboard
     */
    @Query("SELECT NEW map(" +
           "p.projectUniqueId as projectId, " +
           "p.projectName as name, " +
           "p.status as status, " +
           "p.budget as budget, " +
           "p.budgetUtilized as utilized, " +
           "p.budgetUtilizationPercent as utilizationPercent, " +
           "p.totalPoCount as poCount, " +
           "p.totalPoValue as poValue, " +
           "p.deliveredPoCount as deliveredCount, " +
           "p.pendingPaymentValue as pendingPayments) " +
           "FROM ProjectEntity p WHERE p.projectUniqueId = :projectId")
    Optional<Object> getProjectSummary(@Param("projectId") String projectId);

    /**
     * Search projects by name
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true " +
           "AND LOWER(p.projectName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<ProjectEntity> searchByName(@Param("searchTerm") String searchTerm);

    /**
     * Get projects requiring attention (overbudget or behind schedule)
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true " +
           "AND (p.budgetUtilizationPercent > 90 OR " +
           "(p.endDate < CURRENT_DATE AND p.status != 'COMPLETED'))")
    List<ProjectEntity> findProjectsRequiringAttention();

    /**
     * Get projects with highest vendor count
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true " +
           "AND p.activeVendorCount > 0 ORDER BY p.activeVendorCount DESC")
    List<ProjectEntity> findProjectsByVendorCount();

    /**
     * Get projects with recent procurement activity (last 7 days)
     */
    @Query(value = "SELECT * FROM projects WHERE is_active = 1 " +
           "AND last_procurement_update >= DATE_SUB(NOW(), INTERVAL 7 DAY) " +
           "ORDER BY last_procurement_update DESC",
           nativeQuery = true)
    List<ProjectEntity> findRecentlyActiveProjects();

    /**
     * Get profit margin statistics
     */
    @Query("SELECT NEW map(" +
           "AVG(p.profitMarginPercent) as avgMargin, " +
           "MIN(p.profitMarginPercent) as minMargin, " +
           "MAX(p.profitMarginPercent) as maxMargin) " +
           "FROM ProjectEntity p WHERE p.isActive = true AND p.budget > 0")
    Optional<Object> getProfitMarginStatistics();

    /**
     * Get completion rate statistics
     */
    @Query("SELECT NEW map(" +
           "COUNT(CASE WHEN p.status = 'COMPLETED' THEN 1 END) as completed, " +
           "COUNT(CASE WHEN p.status = 'IN_PROGRESS' THEN 1 END) as inProgress, " +
           "COUNT(CASE WHEN p.status = 'PLANNING' THEN 1 END) as planning, " +
           "COUNT(CASE WHEN p.status = 'ON_HOLD' THEN 1 END) as onHold, " +
           "COUNT(CASE WHEN p.status = 'CANCELLED' THEN 1 END) as cancelled, " +
           "COUNT(p) as total) " +
           "FROM ProjectEntity p WHERE p.isActive = true")
    Optional<Object> getStatusDistribution();

    /**
     * Get projects for specific user (created_by or assigned_to)
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.isActive = true " +
           "AND (p.createdBy = :userId OR p.assignedTo = :userId)")
    List<ProjectEntity> findProjectsByUser(@Param("userId") Long userId);

    /**
     * Check if project exists by unique ID
     */
    boolean existsByProjectUniqueId(String projectUniqueId);

    /**
     * Get projects expiring soon (within next 30 days)
     */
    /**
     * Get projects expiring soon (within next 30 days)
     */
    @Query(value = "SELECT * FROM projects WHERE is_active = 1 " +
           "AND end_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY) " +
           "AND status != 'COMPLETED' " +
           "ORDER BY end_date ASC", 
           nativeQuery = true)
    List<ProjectEntity> findProjectsExpiringSoon();

    /**
     * Get total procurement value across all projects
     */
    @Query("SELECT NEW map(" +
           "COALESCE(SUM(p.totalPoValue), 0) as totalPO, " +
           "COALESCE(SUM(p.totalQuotationValue), 0) as totalQuotation, " +
           "COALESCE(SUM(p.totalBillValue), 0) as totalBill, " +
           "COALESCE(SUM(p.totalVendorSpend), 0) as totalVendorSpend) " +
           "FROM ProjectEntity p WHERE p.isActive = true")
    Optional<Object> getGlobalProcurementStats();
    
  
   

        @Query("""
            SELECT p.customerId
            FROM ProjectEntity p
            WHERE p.projectUniqueId = :projectId
        """)
        String findCustomerIdByProjectId(@Param("projectId") String projectId);
    
     // In your existing ProjectRepository.java
        /**
         * Update all expense-related stats on the projects table.
         * Called automatically after every expense / advance create / update / delete.
         */
        @Modifying
        @Query(value = """
            UPDATE projects SET
                total_expense_amount    = :approved,
                pending_expense_count   = :pendingCount,
                pending_expense_amount  = :pendingAmt,
                travel_expense_total    = :travel,
                commission_expense_total = :commission,
                total_advance_amount    = :totalAdv,
                pending_advance_amount  = :unsettledAdv,
                last_expense_update     = CURRENT_TIMESTAMP
            WHERE project_unique_id     = :projectId
        """, nativeQuery = true)
        void updateExpenseStats(
            @Param("projectId")   String projectId,
            @Param("approved")    BigDecimal approved,
            @Param("pendingCount") Integer pendingCount,
            @Param("travel")      BigDecimal travel,
            @Param("commission")  BigDecimal commission,
            @Param("totalAdv")    BigDecimal totalAdv,
            @Param("unsettledAdv") BigDecimal unsettledAdv,
            @Param("pendingAmt")  BigDecimal pendingAmt
        );

    /**
     * Update bill/vendor-payment stats on the projects table.
     * Called from BillService after every bill create / update / delete / payment.
     */
    /**
     * INCREMENTAL decrement — called from VendorAdvanceService.reverseAmountFromBill
     * when a payment allocation is removed/reversed.
     */
    @Modifying
    @Query(value = """
        UPDATE projects SET
            paid_bill_value       = GREATEST(0, paid_bill_value       - :paymentAmount),
            pending_payment_value = pending_payment_value + :paymentAmount,
            paid_bill_count       = GREATEST(0, paid_bill_count       - :paidCountDelta),
            updated_at            = CURRENT_TIMESTAMP
        WHERE project_unique_id   = :projectId
    """, nativeQuery = true)
    void decrementProjectPaidBillValue(
        @Param("projectId")      String projectId,
        @Param("paymentAmount")  BigDecimal paymentAmount,
        @Param("paidCountDelta") int paidCountDelta
    );

    /**
     * INCREMENTAL update — called directly from BillService.addPayment / markAsPaid.
     * Adds paymentAmount to paid_bill_value and subtracts it from pending_payment_value
     * in a single atomic SQL statement — no aggregate queries, no Hibernate cache issues.
     * Also increments paid_bill_count if the bill just became fully Paid.
     */
    @Modifying
    @Query(value = """
        UPDATE projects SET
            paid_bill_value       = paid_bill_value       + :paymentAmount,
            pending_payment_value = GREATEST(0, pending_payment_value - :paymentAmount),
            paid_bill_count       = paid_bill_count       + :paidCountDelta,
            updated_at            = CURRENT_TIMESTAMP
        WHERE project_unique_id   = :projectId
    """, nativeQuery = true)
    void incrementProjectPaidBillValue(
        @Param("projectId")      String projectId,
        @Param("paymentAmount")  BigDecimal paymentAmount,
        @Param("paidCountDelta") int paidCountDelta
    );

    // REMOVED: incrementProjectPaidInvoiceValue.
    //
    // It did "paid_invoice_value = paid_invoice_value + :paymentAmount", making it a
    // second writer for a column that ProjectStatsService.calculateInvoiceStats already
    // assigns outright from SUM(receipts). Two writers with different definitions means
    // the value is only right until one of them next runs. Payment recording now calls
    // InvoiceService.syncProjectInvoiceStats, so "received" has one definition.

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE projects SET
            total_bill_value       = :totalBillValue,
            total_bill_count       = :totalBillCount,
            paid_bill_value        = :paidBillValue,
            paid_bill_count        = :paidBillCount,
            pending_payment_value  = :pendingPaymentValue,
            updated_at             = CURRENT_TIMESTAMP
        WHERE project_unique_id    = :projectId
    """, nativeQuery = true)
    void updateBillStats(
        @Param("projectId")          String projectId,
        @Param("totalBillValue")     BigDecimal totalBillValue,
        @Param("totalBillCount")     Integer totalBillCount,
        @Param("paidBillValue")      BigDecimal paidBillValue,
        @Param("paidBillCount")      Integer paidBillCount,
        @Param("pendingPaymentValue") BigDecimal pendingPaymentValue
    );

    /**
     * Update invoice/client-billing stats on the projects table.
     * Called from InvoiceService after every invoice create / update / delete / payment.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE projects SET
            total_invoice_value    = :totalInvoiceValue,
            total_invoice_count    = :totalInvoiceCount,
            paid_invoice_value     = :paidInvoiceValue,
            paid_invoice_count     = :paidInvoiceCount,
            pending_invoice_value  = :pendingInvoiceValue,
            updated_at             = CURRENT_TIMESTAMP
        WHERE project_unique_id    = :projectId
    """, nativeQuery = true)
    void updateInvoiceStats(
        @Param("projectId")         String projectId,
        @Param("totalInvoiceValue") BigDecimal totalInvoiceValue,
        @Param("totalInvoiceCount") Integer totalInvoiceCount,
        @Param("paidInvoiceValue")  BigDecimal paidInvoiceValue,
        @Param("paidInvoiceCount")  Integer paidInvoiceCount,
        @Param("pendingInvoiceValue") BigDecimal pendingInvoiceValue
    );

    // ── Accessible project group/subgroup lookups ──

    @Query("SELECT DISTINCT p.groupId FROM ProjectEntity p WHERE p.projectUniqueId IN :projectIds AND p.isActive = true AND p.groupId IS NOT NULL")
    List<String> findDistinctGroupIdsByProjectIds(@Param("projectIds") List<String> projectIds);

    @Query("SELECT DISTINCT p.subGroupName FROM ProjectEntity p WHERE p.projectUniqueId IN :projectIds AND p.groupId = :groupId AND p.isActive = true AND p.subGroupName IS NOT NULL")
    List<String> findDistinctSubGroupNamesByProjectIdsAndGroup(@Param("projectIds") List<String> projectIds, @Param("groupId") String groupId);

    @Query("SELECT p FROM ProjectEntity p WHERE p.projectUniqueId IN :projectIds AND p.isActive = true")
    List<ProjectEntity> findByProjectUniqueIdIn(@Param("projectIds") List<String> projectIds);

    /**
     * Projects by unique id, ACTIVE OR NOT — unlike findByProjectUniqueIdIn,
     * which filters isActive = true. ClientFinancialsService needs the inactive
     * ones too, so it can report how many projects it excluded instead of
     * silently losing them.
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.projectUniqueId IN :projectIds")
    List<ProjectEntity> findAllByProjectUniqueIdIn(@Param("projectIds") List<String> projectIds);


    // ── Capacity update ───────────────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query("UPDATE ProjectEntity p SET p.capacityValue = :val, p.capacityUnit = :unit WHERE p.projectUniqueId = :projectId")
    void updateCapacity(@Param("projectId") String projectId,
                        @Param("val") BigDecimal val,
                        @Param("unit") String unit);

    // ── Capacity aggregation for dashboard ────────────────────────────────────

    /** Sum capacity grouped by subGroupName — for GROUP scope */
    @Query("SELECT p.subGroupName, SUM(p.capacityValue), p.capacityUnit, COUNT(p) " +
           "FROM ProjectEntity p " +
           "WHERE p.isActive = true AND p.capacityValue IS NOT NULL " +
           "  AND (:groupId IS NULL OR p.groupId = :groupId) " +
           "GROUP BY p.subGroupName, p.capacityUnit " +
           "ORDER BY p.subGroupName")
    List<Object[]> findCapacityBySubGroup(@Param("groupId") String groupId);

    /** Sum capacity grouped by subGroupName — access-filtered for non-admin users */
    @Query("SELECT p.subGroupName, SUM(p.capacityValue), p.capacityUnit, COUNT(p) " +
           "FROM ProjectEntity p " +
           "WHERE p.isActive = true AND p.capacityValue IS NOT NULL " +
           "  AND (:groupId IS NULL OR p.groupId = :groupId) " +
           "  AND p.projectUniqueId IN :projectIds " +
           "GROUP BY p.subGroupName, p.capacityUnit " +
           "ORDER BY p.subGroupName")
    List<Object[]> findCapacityBySubGroupFiltered(@Param("groupId") String groupId,
                                                   @Param("projectIds") List<String> projectIds);

    /** Per-project capacity for SUBGROUP scope */
    @Query("SELECT p.projectUniqueId, p.projectName, p.capacityValue, p.capacityUnit " +
           "FROM ProjectEntity p " +
           "WHERE p.isActive = true AND p.capacityValue IS NOT NULL " +
           "  AND (:groupId IS NULL OR p.groupId = :groupId) " +
           "  AND (:subGroupName IS NULL OR p.subGroupName = :subGroupName) " +
           "ORDER BY p.capacityValue DESC")
    List<Object[]> findCapacityByProject(@Param("groupId") String groupId,
                                          @Param("subGroupName") String subGroupName);

    /** Per-project capacity for SUBGROUP scope — access-filtered for non-admin users */
    @Query("SELECT p.projectUniqueId, p.projectName, p.capacityValue, p.capacityUnit " +
           "FROM ProjectEntity p " +
           "WHERE p.isActive = true AND p.capacityValue IS NOT NULL " +
           "  AND (:groupId IS NULL OR p.groupId = :groupId) " +
           "  AND (:subGroupName IS NULL OR p.subGroupName = :subGroupName) " +
           "  AND p.projectUniqueId IN :projectIds " +
           "ORDER BY p.capacityValue DESC")
    List<Object[]> findCapacityByProjectFiltered(@Param("groupId") String groupId,
                                                  @Param("subGroupName") String subGroupName,
                                                  @Param("projectIds") List<String> projectIds);

    // ═══ Client Financials roll-up ═══════════════════════════════════════════
    // Both queries below are scalar/entity reads used by ClientFinancialsService.
    // Neither touches the cached summary columns on this table — see that
    // service's header for why those are off limits.

    /**
     * Every project belonging to one client, by customer CODE.
     * projects.customer_id stores the customers.customer_code string (see
     * DropdownProjectService.createProjectFromOrderBook), not the numeric id.
     */
    List<ProjectEntity> findByCustomerId(String customerId);

    /**
     * project_unique_id of every project that counts towards a company-wide
     * total: live and not cancelled. Same rule ClientFinancialsService applies
     * to a client's own projects, so the client share and the company total it
     * is divided by are drawn from exactly the same population.
     */
    @Query(value = "SELECT project_unique_id FROM projects "
                 + "WHERE is_active = 1 AND (status IS NULL OR status <> 'CANCELLED')",
           nativeQuery = true)
    List<String> findEligibleProjectIds();
}
