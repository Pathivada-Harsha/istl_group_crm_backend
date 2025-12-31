package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;

public interface LeadsRepo extends JpaRepository<LeadsEntity, Long> {

    // Find by lead code
    Optional<LeadsEntity> findByLeadCode(String leadCode);

    // Find all non-deleted leads
    List<LeadsEntity> findByDeletedAtIsNull();

    // Find by created by
    List<LeadsEntity> findByCreatedByAndDeletedAtIsNull(Long createdBy);

    // Find by assigned to
    List<LeadsEntity> findByAssignedToAndDeletedAtIsNull(Long assignedTo);

    // Find by created by or assigned to (for regular users)
    @Query("SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL AND (l.createdBy = :userId OR l.assignedTo = :userId)")
    List<LeadsEntity> findByCreatedByOrAssignedTo(@Param("userId") Long userId);

    // Find by group name
    List<LeadsEntity> findByGroupNameAndDeletedAtIsNull(String groupName);

    // Find by status
    List<LeadsEntity> findByStatusAndDeletedAtIsNull(String status);

    // Find by priority
    List<LeadsEntity> findByPriorityAndDeletedAtIsNull(String priority);

    // Find by source
    List<LeadsEntity> findBySourceAndDeletedAtIsNull(String source);

    // Complex search query for filters
    @Query("SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL " +
           "AND (:searchTerm IS NULL OR LOWER(l.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(l.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(l.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(l.leadCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
           "AND (:status IS NULL OR l.status = :status) " +
           "AND (:priority IS NULL OR l.priority = :priority) " +
           "AND (:source IS NULL OR l.source = :source) " +
           "AND (:groupName IS NULL OR l.groupName = :groupName) " +
           "AND (:subGroupName IS NULL OR l.subGroupName = :subGroupName) " +
           "AND (:assignedTo IS NULL OR l.assignedTo = :assignedTo) " +
           "AND (:fromDate IS NULL OR l.createdAt >= :fromDate) " +
           "AND (:toDate IS NULL OR l.createdAt <= :toDate)")
    List<LeadsEntity> searchLeads(
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("priority") String priority,
        @Param("source") String source,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("assignedTo") Long assignedTo,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    // Search leads with user access control (for regular users)
    @Query("SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL " +
           "AND (l.createdBy = :userId OR l.assignedTo = :userId) " +
           "AND (:searchTerm IS NULL OR LOWER(l.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(l.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(l.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(l.leadCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
           "AND (:status IS NULL OR l.status = :status) " +
           "AND (:priority IS NULL OR l.priority = :priority) " +
           "AND (:source IS NULL OR l.source = :source) " +
           "AND (:groupName IS NULL OR l.groupName = :groupName) " +
           "AND (:subGroupName IS NULL OR l.subGroupName = :subGroupName) " +
           "AND (:assignedTo IS NULL OR l.assignedTo = :assignedTo) " +
           "AND (:fromDate IS NULL OR l.createdAt >= :fromDate) " +
           "AND (:toDate IS NULL OR l.createdAt <= :toDate)")
    List<LeadsEntity> searchLeadsForUser(
        @Param("userId") Long userId,
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("priority") String priority,
        @Param("source") String source,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("assignedTo") Long assignedTo,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );

    // Count leads by status
    @Query("SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.status = :status")
    Long countByStatus(@Param("status") String status);

    // Count leads by priority
    @Query("SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.priority = :priority")
    Long countByPriority(@Param("priority") String priority);

    // Get leads created within date range
    @Query("SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.createdAt BETWEEN :startDate AND :endDate")
    List<LeadsEntity> findLeadsByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}