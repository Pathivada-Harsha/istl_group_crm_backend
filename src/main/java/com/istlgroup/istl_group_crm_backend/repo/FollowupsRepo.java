package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;

public interface FollowupsRepo extends JpaRepository<FollowupsEntity, Long> {

    // Find by related type and ID
    List<FollowupsEntity> findByRelatedTypeAndRelatedIdOrderByScheduledAtDesc(String relatedType, Long relatedId);

    // Find by lead ID
    List<FollowupsEntity> findByLeadIdOrderByScheduledAtDesc(Long leadId);

    // Find by customer ID
    List<FollowupsEntity> findByCustomerIdOrderByScheduledAtDesc(Long customerId);

    // Find by assigned to
    List<FollowupsEntity> findByAssignedToOrderByScheduledAtDesc(Long assignedTo);

    // Find by created by
    List<FollowupsEntity> findByCreatedByOrderByScheduledAtDesc(Long createdBy);

    // Find all followups that belong to a user — either assigned to them OR created by them.
    @Query("SELECT f FROM FollowupsEntity f WHERE f.assignedTo = :userId OR f.createdBy = :userId ORDER BY f.scheduledAt DESC")
    List<FollowupsEntity> findByAssignedToOrCreatedByOrderByScheduledAtDesc(@Param("userId") Long userId);

    // ─────────────────────────────────────────────────────────────────────────
    // SERVER-SIDE PAGINATED QUERY (main Follow-ups page)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Paginated, filtered query for the Follow-ups page.
     *
     * Filter rules:
     *  - userId = null       → admin/superadmin, no user restriction
     *  - userId != null      → only rows where assignedTo=userId OR createdBy=userId
     *  - groupName != null   → restrict to that group
     *  - subGroupName != null → further restrict to that subGroup
     *  - status != null      → filter by status
     *  - priority != null    → filter by priority
     *  - followupType != null → filter by type
     *  - assignedToFilter != null → filter by specific assignee
     *  - fromDate / toDate   → date range on scheduledAt
     *  - searchTerm          → LIKE search on notes, leadCode, etc.
     *
     * Sorting is handled by the Pageable (caller passes Sort.by("id").descending()).
     */
    @Query("SELECT f FROM FollowupsEntity f WHERE " +
           "(:userId IS NULL OR f.assignedTo = :userId OR f.createdBy = :userId) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR f.subGroupName = :subGroupName) AND " +
           "(:status IS NULL OR f.status = :status) AND " +
           "(:priority IS NULL OR f.priority = :priority) AND " +
           "(:followupType IS NULL OR f.followupType = :followupType) AND " +
           "(:assignedToFilter IS NULL OR f.assignedTo = :assignedToFilter) AND " +
           "(:fromDate IS NULL OR f.scheduledAt >= :fromDate) AND " +
           "(:toDate IS NULL OR f.scheduledAt <= :toDate) AND " +
           "(:searchTerm IS NULL OR " +
           "  LOWER(f.notes) LIKE :searchTerm OR " +
           "  LOWER(f.outcome) LIKE :searchTerm OR " +
           "  LOWER(f.followupType) LIKE :searchTerm OR " +
           "  LOWER(f.groupName) LIKE :searchTerm)")
    Page<FollowupsEntity> findPagedByFilters(
        @Param("userId") Long userId,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("status") String status,
        @Param("priority") String priority,
        @Param("followupType") String followupType,
        @Param("assignedToFilter") Long assignedToFilter,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );

    // ─────────────────────────────────────────────────────────────────────────
    // KPI COUNT QUERIES (respect user scope + group filters only, ignore other filters)
    // ─────────────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(f) FROM FollowupsEntity f WHERE " +
           "(:userId IS NULL OR f.assignedTo = :userId OR f.createdBy = :userId) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR f.subGroupName = :subGroupName)")
    long countTotal(
        @Param("userId") Long userId,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName
    );

    @Query("SELECT COUNT(f) FROM FollowupsEntity f WHERE " +
           "(:userId IS NULL OR f.assignedTo = :userId OR f.createdBy = :userId) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR f.subGroupName = :subGroupName) AND " +
           "f.status = :status")
    long countByStatus(
        @Param("userId") Long userId,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("status") String status
    );

    @Query("SELECT COUNT(f) FROM FollowupsEntity f WHERE " +
           "(:userId IS NULL OR f.assignedTo = :userId OR f.createdBy = :userId) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR f.subGroupName = :subGroupName) AND " +
           "f.status = 'Pending' AND f.scheduledAt < :now")
    long countOverdue(
        @Param("userId") Long userId,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("now") LocalDateTime now
    );

    @Query("SELECT COUNT(f) FROM FollowupsEntity f WHERE " +
           "(:userId IS NULL OR f.assignedTo = :userId OR f.createdBy = :userId) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR f.subGroupName = :subGroupName) AND " +
           "f.status = 'Pending' AND DATE(f.scheduledAt) = DATE(:today)")
    long countDueToday(
        @Param("userId") Long userId,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("today") LocalDateTime today
    );

    // ─────────────────────────────────────────────────────────────────────────
    // EXISTING QUERIES (kept as-is for other modules)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Non-paginated filtered query (kept for backward compatibility / other uses).
     */
    @Query("SELECT f FROM FollowupsEntity f WHERE " +
           "(:userId IS NULL OR f.assignedTo = :userId OR f.createdBy = :userId) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR f.subGroupName = :subGroupName) " +
           "ORDER BY f.scheduledAt DESC")
    List<FollowupsEntity> findByUserAndFilters(
        @Param("userId") Long userId,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName
    );

    // Find by status
    List<FollowupsEntity> findByStatusOrderByScheduledAtDesc(String status);

    // Find pending followups for a lead
    @Query("SELECT f FROM FollowupsEntity f WHERE f.leadId = :leadId AND f.status = 'Pending' ORDER BY f.scheduledAt ASC")
    List<FollowupsEntity> findPendingByLeadId(@Param("leadId") Long leadId);

    // Count pending followups for a lead
    @Query("SELECT COUNT(f) FROM FollowupsEntity f WHERE f.leadId = :leadId AND f.status = 'Pending'")
    int countPendingByLeadId(@Param("leadId") Long leadId);

    // Find overdue followups
    @Query("SELECT f FROM FollowupsEntity f WHERE f.status = 'Pending' AND f.scheduledAt < :now ORDER BY f.scheduledAt ASC")
    List<FollowupsEntity> findOverdueFollowups(@Param("now") LocalDateTime now);

    // Find today's followups
    @Query("SELECT f FROM FollowupsEntity f WHERE f.status = 'Pending' AND DATE(f.scheduledAt) = DATE(:today) ORDER BY f.scheduledAt ASC")
    List<FollowupsEntity> findTodaysFollowups(@Param("today") LocalDateTime today);

    // Notification scheduler
    @Query("SELECT f FROM FollowupsEntity f WHERE f.status IN ('Pending', 'Rescheduled') ORDER BY f.assignedTo ASC, f.scheduledAt ASC")
    List<FollowupsEntity> findAllPendingOrRescheduledFollowups();

    // Due-today / reminder window
    List<FollowupsEntity> findByStatusInAndScheduledAtBetween(
            List<String> statuses, LocalDateTime start, LocalDateTime end);

    // Overdue
    List<FollowupsEntity> findByStatusInAndScheduledAtBefore(
            List<String> statuses, LocalDateTime before);

    // Search followups (existing, kept for backward compat)
    @Query("SELECT f FROM FollowupsEntity f WHERE " +
           "(:assignedTo IS NULL OR f.assignedTo = :assignedTo) AND " +
           "(:status IS NULL OR f.status = :status) AND " +
           "(:priority IS NULL OR f.priority = :priority) AND " +
           "(:followupType IS NULL OR f.followupType = :followupType) AND " +
           "(:groupName IS NULL OR f.groupName = :groupName) AND " +
           "(:fromDate IS NULL OR f.scheduledAt >= :fromDate) AND " +
           "(:toDate IS NULL OR f.scheduledAt <= :toDate) " +
           "ORDER BY f.scheduledAt DESC")
    List<FollowupsEntity> searchFollowups(
        @Param("assignedTo") Long assignedTo,
        @Param("status") String status,
        @Param("priority") String priority,
        @Param("followupType") String followupType,
        @Param("groupName") String groupName,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );
}