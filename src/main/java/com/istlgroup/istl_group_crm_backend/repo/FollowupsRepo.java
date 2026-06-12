package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
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
    // This is the correct query for the "my followups" page: one DB call, no client-side merging.
    @Query("SELECT f FROM FollowupsEntity f WHERE f.assignedTo = :userId OR f.createdBy = :userId ORDER BY f.scheduledAt DESC")
    List<FollowupsEntity> findByAssignedToOrCreatedByOrderByScheduledAtDesc(@Param("userId") Long userId);

    /**
     * Filtered query for the Follow-ups page.
     *
     * Rules:
     *  - userId = null       → admin/superadmin, no user restriction
     *  - userId != null      → only rows where assignedTo=userId OR createdBy=userId
     *  - groupName != null   → restrict to that group (applies to ALL followup types including CUSTOMER)
     *  - subGroupName != null → further restrict to that subGroup
     *
     * All followup types (LEAD, CUSTOMER, etc.) are treated identically.
     * No relatedType-based bypass — groupName is the only group filter.
     * Customer followups now store groupName/subGroupName from their customer record on creation,
     * so they filter correctly just like lead followups.
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

    // ── NEW: Fetch ALL Pending or Rescheduled followups for daily reminder emails ──
    @Query("SELECT f FROM FollowupsEntity f WHERE f.status IN ('Pending', 'Rescheduled') ORDER BY f.assignedTo ASC, f.scheduledAt ASC")
    List<FollowupsEntity> findAllPendingOrRescheduledFollowups();
    
    // Search followups
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

    // ── Notification scheduler (additive) ─────────────────────────────
    // Due-today / reminder window: status IN (...) AND scheduledAt BETWEEN start AND end
    List<FollowupsEntity> findByStatusInAndScheduledAtBetween(
            List<String> statuses, LocalDateTime start, LocalDateTime end);

    // Overdue: status IN (...) AND scheduledAt < before
    List<FollowupsEntity> findByStatusInAndScheduledAtBefore(
            List<String> statuses, LocalDateTime before);
}