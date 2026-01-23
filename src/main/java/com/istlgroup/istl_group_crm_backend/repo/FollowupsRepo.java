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
    
    // Find by assigned to
    List<FollowupsEntity> findByAssignedToOrderByScheduledAtDesc(Long assignedTo);
    
    // Find by created by
    List<FollowupsEntity> findByCreatedByOrderByScheduledAtDesc(Long createdBy);
    
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
}