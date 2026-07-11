package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ProjectExpense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ProjectExpenseRepository extends JpaRepository<ProjectExpense, Long> {

    // ─── Filtered paginated list (no visibility restriction — admin only) ──────
    @Query("""
        SELECT DISTINCT e FROM ProjectExpense e
        LEFT JOIN e.expenseItems i
        WHERE e.isDeleted = false
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:status       IS NULL OR e.status       = :status)
          AND (:category     IS NULL OR i.category     = :category)
          AND (:paymentMode  IS NULL OR i.paymentMode  = :paymentMode)
          AND (:dateFrom     IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate <= :dateTo)
          AND (:search IS NULL
               OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(i.description) LIKE LOWER(CONCAT('%',:search,'%')))
    """)
    Page<ProjectExpense> findWithFilters(
        @Param("groupName")    String    groupName,
        @Param("subGroupName") String    subGroupName,
        @Param("projectId")    String    projectId,
        @Param("category")     String    category,
        @Param("status")       String    status,
        @Param("paymentMode")  String    paymentMode,
        @Param("dateFrom")     LocalDate dateFrom,
        @Param("dateTo")       LocalDate dateTo,
        @Param("search")       String    search,
        Pageable pageable
    );

    /**
     * Role-aware query — restricts records by user visibility.
     *
     * Visibility logic (all applied as AND on top of the standard filters):
     *   viewerUserId = null  → no restriction  (SUPERADMIN / ADMIN)
     *   viewerUserId != null → record must satisfy:
     *       createdBy = viewerUserId
     *       OR paidByUserId = viewerUserId
     *       OR (teamMemberIds not empty AND (createdBy IN teamMemberIds OR paidByUserId IN teamMemberIds))
     *
     * The teamMemberIds list is pre-fetched by the service from the users table
     * (users where manager_id = viewerUserId) so the JPQL stays simple.
     * When teamMemberIds is empty we pass a list containing -1L so the IN clause
     * never accidentally matches a real user.
     */
    @Query("""
        SELECT DISTINCT e FROM ProjectExpense e
        LEFT JOIN e.expenseItems i
        WHERE e.isDeleted = false
          AND (:bdOnly = FALSE OR e.projectId IS NULL)
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:status       IS NULL OR e.status       = :status)
          AND (:category     IS NULL OR i.category     = :category)
          AND (:paymentMode  IS NULL OR i.paymentMode  = :paymentMode)
          AND (:dateFrom     IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate <= :dateTo)
          AND (:search IS NULL
               OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(i.description) LIKE LOWER(CONCAT('%',:search,'%')))
          AND (:viewerUserId IS NULL
               OR e.createdBy     = :viewerUserId
               OR e.paidByUserId  = :viewerUserId
               OR e.createdBy    IN :teamMemberIds
               OR e.paidByUserId IN :teamMemberIds)
    """)
    Page<ProjectExpense> findWithFiltersAndVisibility(
        @Param("groupName")     String    groupName,
        @Param("subGroupName")  String    subGroupName,
        @Param("projectId")     String    projectId,
        @Param("category")      String    category,
        @Param("status")        String    status,
        @Param("paymentMode")   String    paymentMode,
        @Param("dateFrom")      LocalDate dateFrom,
        @Param("dateTo")        LocalDate dateTo,
        @Param("search")        String    search,
        @Param("viewerUserId")  Long      viewerUserId,
        @Param("teamMemberIds") List<Long> teamMemberIds,
        @Param("bdOnly")        boolean   bdOnly,
        Pageable pageable
    );

    /**
     * Project-access-list query — for non-admin users who have explicit project grants.
     * Scopes results to the given projectIds list; group/subGroup act as additional filters.
     */
    @Query("""
        SELECT DISTINCT e FROM ProjectExpense e
        LEFT JOIN e.expenseItems i
        WHERE e.isDeleted = false
          AND ( e.projectId IN :projectIds
                OR (e.projectId IS NULL
                    AND (e.createdBy = :viewerUserId OR e.paidByUserId = :viewerUserId)) )
          AND (:bdOnly = FALSE OR e.projectId IS NULL)
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:status       IS NULL OR e.status       = :status)
          AND (:category     IS NULL OR i.category     = :category)
          AND (:paymentMode  IS NULL OR i.paymentMode  = :paymentMode)
          AND (:dateFrom     IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate <= :dateTo)
          AND (:search IS NULL
               OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(i.description) LIKE LOWER(CONCAT('%',:search,'%')))
    """)
    Page<ProjectExpense> findWithFiltersAndVisibilityByProjects(
        @Param("projectIds")    List<String> projectIds,
        @Param("viewerUserId")  Long      viewerUserId,
        @Param("groupName")     String    groupName,
        @Param("subGroupName")  String    subGroupName,
        @Param("category")      String    category,
        @Param("status")        String    status,
        @Param("paymentMode")   String    paymentMode,
        @Param("dateFrom")      LocalDate dateFrom,
        @Param("dateTo")        LocalDate dateTo,
        @Param("search")        String    search,
        @Param("bdOnly")        boolean   bdOnly,
        Pageable pageable
    );

    // Total Business Development (project-less) expenses. viewerUserId null → all
    // (admin); otherwise scoped to the viewer's own created/paid-by BD rows.
    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.projectId IS NULL
          AND (:viewerUserId IS NULL OR e.createdBy=:viewerUserId OR e.paidByUserId=:viewerUserId)
    """)
    BigDecimal sumBusinessDevelopment(@Param("viewerUserId") Long viewerUserId);

    // ─── Project-level aggregates (use totalAmount on parent) ─────────────────
    @Query("SELECT SUM(e.totalAmount) FROM ProjectExpense e WHERE e.projectId=:pid AND e.status='Approved' AND e.isDeleted=false")
    BigDecimal sumApprovedByProject(@Param("pid") String pid);

    @Query("SELECT SUM(e.totalAmount) FROM ProjectExpense e WHERE e.projectId=:pid AND e.status='Pending' AND e.isDeleted=false")
    BigDecimal sumPendingByProject(@Param("pid") String pid);

    @Query("SELECT COUNT(e) FROM ProjectExpense e WHERE e.projectId=:pid AND e.status='Pending' AND e.isDeleted=false")
    Long countPending(@Param("pid") String pid);

    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.projectId=:pid AND e.status='Approved' AND e.isDeleted=false
          AND MONTH(e.tripDate)=MONTH(CURRENT_DATE) AND YEAR(e.tripDate)=YEAR(CURRENT_DATE)
    """)
    BigDecimal sumApprovedThisMonth(@Param("pid") String pid);

    // Travel & Site Visit — sum item amounts where category matches
    @Query("""
        SELECT SUM(i.amount) FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.projectId=:pid AND e.isDeleted=false
          AND i.category IN ('Travel', 'Site Visit')
    """)
    BigDecimal sumTravelAndSiteVisit(@Param("pid") String pid);

    // Commission — sum item amounts where category = Commission
    @Query("""
        SELECT SUM(i.amount) FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.projectId=:pid AND e.isDeleted=false
          AND i.category='Commission'
    """)
    BigDecimal sumCommission(@Param("pid") String pid);

    // ─── Group/subgroup-level aggregates with full filter support ───────────────
    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Approved'
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:paymentMode  IS NULL OR EXISTS (SELECT 1 FROM ProjectExpenseItem i WHERE i.expense=e AND i.paymentMode=:paymentMode))
          AND (:dateFrom     IS NULL OR e.tripDate    >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate    <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND (:createdBy    IS NULL OR e.createdBy   = :createdBy
               OR (e.createdBy IN :teamMemberIds))
    """)
    BigDecimal sumAllApproved(
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId, @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search, @Param("createdBy") Long createdBy,
        @Param("teamMemberIds") java.util.List<Long> teamMemberIds);

    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Pending'
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:paymentMode  IS NULL OR EXISTS (SELECT 1 FROM ProjectExpenseItem i WHERE i.expense=e AND i.paymentMode=:paymentMode))
          AND (:dateFrom     IS NULL OR e.tripDate    >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate    <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND (:createdBy    IS NULL OR e.createdBy   = :createdBy
               OR (e.createdBy IN :teamMemberIds))
    """)
    BigDecimal sumAllPending(
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId, @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search, @Param("createdBy") Long createdBy,
        @Param("teamMemberIds") java.util.List<Long> teamMemberIds);

    @Query("""
        SELECT COUNT(e) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Pending'
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:dateFrom     IS NULL OR e.tripDate    >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate    <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND (:createdBy    IS NULL OR e.createdBy   = :createdBy
               OR (e.createdBy IN :teamMemberIds))
    """)
    Long countAllPending(
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search, @Param("createdBy") Long createdBy,
        @Param("teamMemberIds") java.util.List<Long> teamMemberIds);

    // ─── Global category sums with full filter support ────────────────────────
    @Query("""
        SELECT SUM(i.amount) FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.isDeleted=false
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:paymentMode  IS NULL OR i.paymentMode  = :paymentMode)
          AND (:dateFrom     IS NULL OR e.tripDate    >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate    <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND (:createdBy    IS NULL OR e.createdBy   = :createdBy
               OR (e.createdBy IN :teamMemberIds))
          AND i.category IN ('Travel', 'Site Visit')
    """)
    BigDecimal sumAllTravelAndSiteVisit(
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId, @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search, @Param("createdBy") Long createdBy,
        @Param("teamMemberIds") java.util.List<Long> teamMemberIds);

    @Query("""
        SELECT SUM(i.amount) FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.isDeleted=false
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:projectId    IS NULL OR e.projectId    = :projectId)
          AND (:paymentMode  IS NULL OR i.paymentMode  = :paymentMode)
          AND (:dateFrom     IS NULL OR e.tripDate    >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate    <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND (:createdBy    IS NULL OR e.createdBy   = :createdBy
               OR (e.createdBy IN :teamMemberIds))
          AND i.category = 'Commission'
    """)
    BigDecimal sumAllCommission(
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId, @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search, @Param("createdBy") Long createdBy,
        @Param("teamMemberIds") java.util.List<Long> teamMemberIds);

    @Query("""
        SELECT SUM(e.totalAdvanceAmount) FROM ProjectAdvance e
        WHERE (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
    """)
    BigDecimal sumAllAdvances(@Param("groupName") String groupName, @Param("subGroupName") String subGroupName);

    // ─── Project-access-list stats queries (for non-admin users with project grants) ──
    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Approved'
          AND e.projectId IN :projectIds
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:paymentMode  IS NULL OR EXISTS (SELECT 1 FROM ProjectExpenseItem i WHERE i.expense=e AND i.paymentMode=:paymentMode))
          AND (:dateFrom     IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
    """)
    BigDecimal sumApprovedByProjects(
        @Param("projectIds") List<String> projectIds,
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search);

    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Pending'
          AND e.projectId IN :projectIds
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:paymentMode  IS NULL OR EXISTS (SELECT 1 FROM ProjectExpenseItem i WHERE i.expense=e AND i.paymentMode=:paymentMode))
          AND (:dateFrom     IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo       IS NULL OR e.tripDate <= :dateTo)
          AND (:search       IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                     OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
    """)
    BigDecimal sumPendingByProjects(
        @Param("projectIds") List<String> projectIds,
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search);

    @Query("""
        SELECT COUNT(e) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Pending'
          AND e.projectId IN :projectIds
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:dateFrom IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo   IS NULL OR e.tripDate <= :dateTo)
          AND (:search   IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                 OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                 OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
    """)
    Long countPendingByProjects(
        @Param("projectIds") List<String> projectIds,
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search);

    @Query("""
        SELECT SUM(i.amount) FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.isDeleted=false
          AND e.projectId IN :projectIds
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:paymentMode IS NULL OR i.paymentMode = :paymentMode)
          AND (:dateFrom    IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo      IS NULL OR e.tripDate <= :dateTo)
          AND (:search      IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                    OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                    OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND i.category IN ('Travel', 'Site Visit')
    """)
    BigDecimal sumTravelAndSiteVisitByProjects(
        @Param("projectIds") List<String> projectIds,
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search);

    @Query("""
        SELECT SUM(i.amount) FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.isDeleted=false
          AND e.projectId IN :projectIds
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:paymentMode IS NULL OR i.paymentMode = :paymentMode)
          AND (:dateFrom    IS NULL OR e.tripDate >= :dateFrom)
          AND (:dateTo      IS NULL OR e.tripDate <= :dateTo)
          AND (:search      IS NULL OR LOWER(e.expenseCode) LIKE LOWER(CONCAT('%',:search,'%'))
                                    OR LOWER(e.tripReason)  LIKE LOWER(CONCAT('%',:search,'%'))
                                    OR LOWER(e.paidByName)  LIKE LOWER(CONCAT('%',:search,'%')))
          AND i.category = 'Commission'
    """)
    BigDecimal sumCommissionByProjects(
        @Param("projectIds") List<String> projectIds,
        @Param("groupName") String groupName, @Param("subGroupName") String subGroupName,
        @Param("paymentMode") String paymentMode,
        @Param("dateFrom") java.time.LocalDate dateFrom, @Param("dateTo") java.time.LocalDate dateTo,
        @Param("search") String search);

    // ─── Role-aware aggregate queries for KPI cards ────────────────────────────
    // viewerUserId = null  → no user restriction (admin/accounts)
    // viewerUserId != null → only records where createdBy=viewer OR paidBy=viewer OR
    //                        createdBy/paidBy IN teamMemberIds (manager's team)
    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Approved'
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:viewerUserId IS NULL
               OR e.createdBy    = :viewerUserId
               OR e.paidByUserId = :viewerUserId
               OR e.createdBy    IN :teamMemberIds
               OR e.paidByUserId IN :teamMemberIds)
    """)
    BigDecimal sumApprovedWithVisibility(
        @Param("groupName")     String    groupName,
        @Param("subGroupName")  String    subGroupName,
        @Param("viewerUserId")  Long      viewerUserId,
        @Param("teamMemberIds") List<Long> teamMemberIds
    );

    @Query("""
        SELECT SUM(e.totalAmount) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Pending'
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:viewerUserId IS NULL
               OR e.createdBy    = :viewerUserId
               OR e.paidByUserId = :viewerUserId
               OR e.createdBy    IN :teamMemberIds
               OR e.paidByUserId IN :teamMemberIds)
    """)
    BigDecimal sumPendingWithVisibility(
        @Param("groupName")     String    groupName,
        @Param("subGroupName")  String    subGroupName,
        @Param("viewerUserId")  Long      viewerUserId,
        @Param("teamMemberIds") List<Long> teamMemberIds
    );

    @Query("""
        SELECT COUNT(e) FROM ProjectExpense e
        WHERE e.isDeleted=false AND e.status='Pending'
          AND (:groupName    IS NULL OR e.groupName    = :groupName)
          AND (:subGroupName IS NULL OR e.subGroupName = :subGroupName)
          AND (:viewerUserId IS NULL
               OR e.createdBy    = :viewerUserId
               OR e.paidByUserId = :viewerUserId
               OR e.createdBy    IN :teamMemberIds
               OR e.paidByUserId IN :teamMemberIds)
    """)
    Long countPendingWithVisibility(
        @Param("groupName")     String    groupName,
        @Param("subGroupName")  String    subGroupName,
        @Param("viewerUserId")  Long      viewerUserId,
        @Param("teamMemberIds") List<Long> teamMemberIds
    );

    // ─── User breakdown ────────────────────────────────────────────────────────
    @Query("""
        SELECT e.paidByUserId, e.paidByName, SUM(e.totalAmount), COUNT(e),
               SUM(CASE WHEN e.status='Approved' THEN e.totalAmount ELSE 0 END),
               SUM(CASE WHEN e.status='Pending'  THEN e.totalAmount ELSE 0 END)
        FROM ProjectExpense e
        WHERE e.projectId=:pid AND e.isDeleted=false
        GROUP BY e.paidByUserId, e.paidByName ORDER BY SUM(e.totalAmount) DESC
    """)
    List<Object[]> getUserBreakdownByProject(@Param("pid") String pid);

    // ─── Category breakdown (from items) ──────────────────────────────────────
    @Query("""
        SELECT i.category, SUM(i.amount), COUNT(i)
        FROM ProjectExpense e
        JOIN e.expenseItems i
        WHERE e.projectId=:pid AND e.isDeleted=false
        GROUP BY i.category ORDER BY SUM(i.amount) DESC
    """)
    List<Object[]> getCategoryBreakdownByProject(@Param("pid") String pid);

    // ─── Misc ──────────────────────────────────────────────────────────────────
    List<ProjectExpense> findTop5ByProjectIdAndIsDeletedFalseOrderByTripDateDesc(String projectId);
    boolean existsByExpenseCode(String expenseCode);
}