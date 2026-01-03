package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FollowupsRepo extends JpaRepository<FollowupsEntity, Long> {
    
    /**
     * Find followups by related type and ID
     */
    List<FollowupsEntity> findByRelatedTypeAndRelatedIdOrderByScheduledAtDesc(String relatedType, Long relatedId);
    
    /**
     * Find followups created by a user
     */
    List<FollowupsEntity> findByCreatedByOrderByScheduledAtDesc(Long createdBy);
    
    /**
     * Find followups assigned to a user
     */
    List<FollowupsEntity> findByAssignedToOrderByScheduledAtDesc(Long assignedTo);
    
    /**
     * Find followups by status
     */
    List<FollowupsEntity> findByStatusOrderByScheduledAtDesc(String status);
    
    /**
     * Find pending followups for a specific lead
     */
    @Query("SELECT f FROM FollowupsEntity f WHERE " +
           "f.relatedType = 'Lead' AND " +
           "f.relatedId = :leadId AND " +
           "f.status = 'Pending' " +
           "ORDER BY f.scheduledAt ASC")
    List<FollowupsEntity> findPendingFollowupsForLead(@Param("leadId") Long leadId);
    
    /**
     * Find pending followups for a specific customer
     */
    @Query("SELECT f FROM FollowupsEntity f WHERE " +
           "f.relatedType = 'Customer' AND " +
           "f.relatedId = :customerId AND " +
           "f.status = 'Pending' " +
           "ORDER BY f.scheduledAt ASC")
    List<FollowupsEntity> findPendingFollowupsForCustomer(@Param("customerId") Long customerId);
    
    /**
     * Check if lead has pending followups
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END FROM FollowupsEntity f WHERE " +
           "f.relatedType = 'Lead' AND " +
           "f.relatedId = :leadId AND " +
           "f.status = 'Pending'")
    boolean hasPendingFollowupsForLead(@Param("leadId") Long leadId);
    
    /**
     * Check if customer has pending followups
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END FROM FollowupsEntity f WHERE " +
           "f.relatedType = 'Customer' AND " +
           "f.relatedId = :customerId AND " +
           "f.status = 'Pending'")
    boolean hasPendingFollowupsForCustomer(@Param("customerId") Long customerId);
}