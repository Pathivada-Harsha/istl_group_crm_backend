package com.istlgroup.istl_group_crm_backend.repo;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    
 
}
   
   

























































