package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;

public interface LeadsRepo extends JpaRepository<LeadsEntity, Long> {

    // Find by lead code
    Optional<LeadsEntity> findByLeadCode(String leadCode);

    // Find all non-deleted leads
    List<LeadsEntity> findByDeletedAtIsNullOrderByCreatedAtDesc();

    // Find all non-deleted leads - PAGINATED
    Page<LeadsEntity> findByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    // Find by created by
    List<LeadsEntity> findByCreatedByAndDeletedAtIsNullOrderByCreatedAtDesc(Long createdBy);

    // Find by assigned to
    List<LeadsEntity> findByAssignedToAndDeletedAtIsNullOrderByCreatedAtDesc(Long assignedTo);

    // Find by created by or assigned to (for regular users)
    @Query("SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL AND (l.createdBy = :userId OR l.assignedTo = :userId) ORDER BY l.createdAt DESC")
    List<LeadsEntity> findByCreatedByOrAssignedTo(@Param("userId") Long userId);

    // Find by created by or assigned to (for regular users) - PAGINATED
    @Query(value = "SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL AND (l.createdBy = :userId OR l.assignedTo = :userId) ORDER BY l.createdAt DESC",
           countQuery = "SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL AND (l.createdBy = :userId OR l.assignedTo = :userId)")
    Page<LeadsEntity> findByCreatedByOrAssignedToPaged(@Param("userId") Long userId, Pageable pageable);

    // Find by group name
    List<LeadsEntity> findByGroupNameAndDeletedAtIsNullOrderByCreatedAtDesc(String groupName);

    // Find by status
    List<LeadsEntity> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(String status);

    // Find by priority
    List<LeadsEntity> findByPriorityAndDeletedAtIsNullOrderByCreatedAtDesc(String priority);

    // Find by source
    List<LeadsEntity> findBySourceAndDeletedAtIsNullOrderByCreatedAtDesc(String source);

    // Complex search query for filters (non-paginated, kept for backwards compat)
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
           "AND (:toDate IS NULL OR l.createdAt <= :toDate) ORDER BY l.createdAt DESC")
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

    // ─── PAGINATED search queries (L1/L2 — all leads) ─────────────────────────

    @Query(value = "SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL " +
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
           "AND (:toDate IS NULL OR l.createdAt <= :toDate)",
           countQuery = "SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL " +
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
    Page<LeadsEntity> searchLeadsPaged(
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("priority") String priority,
        @Param("source") String source,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("assignedTo") Long assignedTo,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );

    // Search leads with user access control (for regular users) - non-paginated
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
           "AND (:toDate IS NULL OR l.createdAt <= :toDate) ORDER BY l.createdAt DESC")
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

    // Search leads with user access control - PAGINATED
    @Query(value = "SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL " +
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
           "AND (:toDate IS NULL OR l.createdAt <= :toDate)",
           countQuery = "SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL " +
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
    Page<LeadsEntity> searchLeadsForUserPaged(
        @Param("userId") Long userId,
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("priority") String priority,
        @Param("source") String source,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("assignedTo") Long assignedTo,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );

    // Count leads by status
    @Query("SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.status = :status")
    Long countByStatus(@Param("status") String status);

    // Count leads by priority
    @Query("SELECT COUNT(l) FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.priority = :priority")
    Long countByPriority(@Param("priority") String priority);

    // Get leads created within date range
    @Query("SELECT l FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.createdAt BETWEEN :startDate AND :endDate ORDER BY l.createdAt DESC")
    List<LeadsEntity> findLeadsByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT l FROM LeadsEntity l " +
            "WHERE l.deletedAt IS NULL " +
            "AND UPPER(l.telecallerStatus) = 'NOT_RESPONDED' " +
            "AND l.telecallerStatusUpdatedAt <= :cutoff")
     List<LeadsEntity> findNotRespondedLeadsOlderThan(
             @Param("cutoff") LocalDateTime cutoff);

     /**
      * Used by LeadsImportService — count existing emails to detect duplicates.
      */
     @Query("SELECT l.email FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.email IS NOT NULL")
     List<String> findAllActiveEmails();

     /**
      * Used by LeadsImportService — phone-based duplicate detection.
      */
     @Query("SELECT l.phone FROM LeadsEntity l WHERE l.deletedAt IS NULL AND l.phone IS NOT NULL")
     List<String> findAllActivePhones();

     @Query("SELECT DISTINCT l FROM LeadsEntity l " +
             "WHERE l.deletedAt IS NULL " +
             "AND (l.createdBy = :userId " +
             "     OR l.assignedTo = :userId " +
             "     OR EXISTS (SELECT a FROM LeadAccessEntity a " +
             "                WHERE a.leadId = l.id AND a.userId = :userId)) " +
             "ORDER BY l.createdAt DESC")
      List<LeadsEntity> findAccessibleByUser(@Param("userId") Long userId);

      /**
       * Paginated + filtered: leads accessible to a non-admin user.
       * Checks createdBy OR assignedTo OR lead_access entry.
       */
      @Query(value =
             "SELECT DISTINCT l FROM LeadsEntity l " +
             "WHERE l.deletedAt IS NULL " +
             "AND (l.createdBy = :userId " +
             "     OR l.assignedTo = :userId " +
             "     OR EXISTS (SELECT a FROM LeadAccessEntity a " +
             "                WHERE a.leadId = l.id AND a.userId = :userId)) " +
             "AND (:searchTerm IS NULL " +
             "     OR LOWER(l.name)     LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
             "     OR LOWER(l.email)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
             "     OR LOWER(l.phone)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
             "     OR LOWER(l.leadCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
             "AND (:status       IS NULL OR l.status       = :status) " +
             "AND (:priority     IS NULL OR l.priority     = :priority) " +
             "AND (:source       IS NULL OR l.source       = :source) " +
             "AND (:groupName    IS NULL OR l.groupName    = :groupName) " +
             "AND (:subGroupName IS NULL OR l.subGroupName = :subGroupName) " +
             "AND (:assignedTo   IS NULL OR l.assignedTo   = :assignedTo) " +
             "AND (:fromDate     IS NULL OR l.createdAt   >= :fromDate) " +
             "AND (:toDate       IS NULL OR l.createdAt   <= :toDate)",
             countQuery =
             "SELECT COUNT(DISTINCT l.id) FROM LeadsEntity l " +
             "WHERE l.deletedAt IS NULL " +
             "AND (l.createdBy = :userId " +
             "     OR l.assignedTo = :userId " +
             "     OR EXISTS (SELECT a FROM LeadAccessEntity a " +
             "                WHERE a.leadId = l.id AND a.userId = :userId)) " +
             "AND (:searchTerm IS NULL " +
             "     OR LOWER(l.name)     LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
             "     OR LOWER(l.email)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
             "     OR LOWER(l.phone)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
             "     OR LOWER(l.leadCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
             "AND (:status       IS NULL OR l.status       = :status) " +
             "AND (:priority     IS NULL OR l.priority     = :priority) " +
             "AND (:source       IS NULL OR l.source       = :source) " +
             "AND (:groupName    IS NULL OR l.groupName    = :groupName) " +
             "AND (:subGroupName IS NULL OR l.subGroupName = :subGroupName) " +
             "AND (:assignedTo   IS NULL OR l.assignedTo   = :assignedTo) " +
             "AND (:fromDate     IS NULL OR l.createdAt   >= :fromDate) " +
             "AND (:toDate       IS NULL OR l.createdAt   <= :toDate)")
      Page<LeadsEntity> searchAccessibleByUserPaged(
          @Param("userId")       Long userId,
          @Param("searchTerm")   String searchTerm,
          @Param("status")       String status,
          @Param("priority")     String priority,
          @Param("source")       String source,
          @Param("groupName")    String groupName,
          @Param("subGroupName") String subGroupName,
          @Param("assignedTo")   Long assignedTo,
          @Param("fromDate")     LocalDateTime fromDate,
          @Param("toDate")       LocalDateTime toDate,
          Pageable pageable
      );

    // ─────────────────────────────────────────────────────────────────────────
    // L3 TEAM-SCOPED QUERIES
    // Used when role_hierarchy.level_order = 3 (e.g. MANAGER, BD_MANAGER,
    // SALES_MANAGER, or any future L3 role). Shows all leads where any member
    // of the requesting user's team is the creator or assignee.
    // No role names are hardcoded here — the service resolves the level.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Non-paginated: all leads belonging to any member of the L3 user's team(s).
     * memberIds = result of TeamRepository.findTeamMemberIdsByUserId(managerId).
     */
    @Query("SELECT DISTINCT l FROM LeadsEntity l " +
           "WHERE l.deletedAt IS NULL " +
           "AND (l.createdBy IN :memberIds OR l.assignedTo IN :memberIds) " +
           "ORDER BY l.createdAt DESC")
    List<LeadsEntity> findByTeamMemberLeads(@Param("memberIds") List<Long> memberIds);

    /**
     * Paginated + filtered: leads belonging to the L3 user's team(s).
     * Supports all the same filters as searchLeadsPaged / searchLeadsForUserPaged.
     */
    @Query(value =
           "SELECT DISTINCT l FROM LeadsEntity l " +
           "WHERE l.deletedAt IS NULL " +
           "AND (l.createdBy IN :memberIds OR l.assignedTo IN :memberIds) " +
           "AND (:searchTerm IS NULL " +
           "     OR LOWER(l.name)     LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "     OR LOWER(l.email)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "     OR LOWER(l.phone)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "     OR LOWER(l.leadCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
           "AND (:status       IS NULL OR l.status       = :status) " +
           "AND (:priority     IS NULL OR l.priority     = :priority) " +
           "AND (:source       IS NULL OR l.source       = :source) " +
           "AND (:groupName    IS NULL OR l.groupName    = :groupName) " +
           "AND (:subGroupName IS NULL OR l.subGroupName = :subGroupName) " +
           "AND (:assignedTo   IS NULL OR l.assignedTo   = :assignedTo) " +
           "AND (:fromDate     IS NULL OR l.createdAt   >= :fromDate) " +
           "AND (:toDate       IS NULL OR l.createdAt   <= :toDate)",
           countQuery =
           "SELECT COUNT(DISTINCT l.id) FROM LeadsEntity l " +
           "WHERE l.deletedAt IS NULL " +
           "AND (l.createdBy IN :memberIds OR l.assignedTo IN :memberIds) " +
           "AND (:searchTerm IS NULL " +
           "     OR LOWER(l.name)     LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "     OR LOWER(l.email)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "     OR LOWER(l.phone)    LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "     OR LOWER(l.leadCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
           "AND (:status       IS NULL OR l.status       = :status) " +
           "AND (:priority     IS NULL OR l.priority     = :priority) " +
           "AND (:source       IS NULL OR l.source       = :source) " +
           "AND (:groupName    IS NULL OR l.groupName    = :groupName) " +
           "AND (:subGroupName IS NULL OR l.subGroupName = :subGroupName) " +
           "AND (:assignedTo   IS NULL OR l.assignedTo   = :assignedTo) " +
           "AND (:fromDate     IS NULL OR l.createdAt   >= :fromDate) " +
           "AND (:toDate       IS NULL OR l.createdAt   <= :toDate)")
    Page<LeadsEntity> searchLeadsForTeamPaged(
        @Param("memberIds")    List<Long> memberIds,
        @Param("searchTerm")   String searchTerm,
        @Param("status")       String status,
        @Param("priority")     String priority,
        @Param("source")       String source,
        @Param("groupName")    String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("assignedTo")   Long assignedTo,
        @Param("fromDate")     LocalDateTime fromDate,
        @Param("toDate")       LocalDateTime toDate,
        Pageable pageable
    );

    // ── Closed-by access checks (used by CustomersService.hasAccessToCustomer) ─

    /** True if any lead with this customerId was closed by this specific user. */
    @Query("SELECT COUNT(l) > 0 FROM LeadsEntity l WHERE l.customerId = :customerId AND l.closedByUserId = :userId")
    boolean existsByCustomerIdAndClosedByUserId(
        @Param("customerId") Long customerId,
        @Param("userId")     Long userId);

    /** True if any lead with this customerId was closed by any of these users (L3 team check). */
    @Query("SELECT COUNT(l) > 0 FROM LeadsEntity l WHERE l.customerId = :customerId AND l.closedByUserId IN :memberIds")
    boolean existsByCustomerIdAndClosedByUserIdIn(
        @Param("customerId") Long customerId,
        @Param("memberIds")  List<Long> memberIds);

    /** Find the lead that converted into this customer, to retrieve closedByName for display. */
    @Query("SELECT l FROM LeadsEntity l WHERE l.customerId = :customerId AND l.closedByUserId IS NOT NULL ORDER BY l.updatedAt DESC")
    java.util.Optional<LeadsEntity> findFirstByCustomerIdAndClosedByUserIdIsNotNull(
        @Param("customerId") Long customerId);
}