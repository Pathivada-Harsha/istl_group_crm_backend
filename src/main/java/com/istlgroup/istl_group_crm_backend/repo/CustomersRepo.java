package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomersRepo extends JpaRepository<CustomersEntity, Long> {
    
    /**
     * Count customers by code prefix (for code generation)
     */
    long countByCustomerCodeStartingWith(String prefix);
    
    /**
     * Find customer by customer code
     */
    Optional<CustomersEntity> findByCustomerCode(String customerCode);
    
    // ==================== NON-PAGINATED METHODS ====================
    
    /**
     * Find all active customers (not deleted)
     */
    List<CustomersEntity> findByDeletedAtIsNull();
    
    /**
     * Find customers created by a specific user OR assigned to them
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(c.createdBy = :userId OR c.assignedTo = :userId)")
    List<CustomersEntity> findByCreatedByOrAssignedToAndDeletedAtIsNull(@Param("userId") Long userId);
    
    /**
     * Find customers created by a specific user OR assigned to them with pagination
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(c.createdBy = :userId OR c.assignedTo = :userId)")
    Page<CustomersEntity> findByCreatedByOrAssignedToAndDeletedAtIsNull(@Param("userId") Long userId, Pageable pageable);
    
    /**
     * Find by group name for specific user (created by OR assigned to) with pagination
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(c.createdBy = :userId OR c.assignedTo = :userId) AND " +
           "c.groupName = :groupName")
    Page<CustomersEntity> findByUserAndGroupNameAndDeletedAtIsNull(
        @Param("userId") Long userId, 
        @Param("groupName") String  groupName, 
        Pageable pageable
    );
    
    /**
     * Find customers by status and not deleted
     */
    List<CustomersEntity> findByStatusAndDeletedAtIsNull(String  status);
    
    /**
     * Find customers assigned to a user
     */
    List<CustomersEntity> findByAssignedToAndDeletedAtIsNull(Long assignedTo);
    
    /**
     * Find customers by group name
     */
    List<CustomersEntity> findByGroupNameAndDeletedAtIsNull(String  groupName);
    
    // ==================== PAGINATED METHODS ====================
    
    /**
     * Find all active customers with pagination
     */
    Page<CustomersEntity> findByDeletedAtIsNull(Pageable pageable);
    
    /**
     * Find customers created by a specific user with pagination
     */
    Page<CustomersEntity> findByCreatedByAndDeletedAtIsNull(Long createdBy, Pageable pageable);
    
    /**
     * Find by group name with pagination
     */
    Page<CustomersEntity> findByGroupNameAndDeletedAtIsNull(String groupName, Pageable pageable);
    
    /**
     * Find by created by and group name with pagination (deprecated - use findByUserAndGroupNameAndDeletedAtIsNull)
     */
    Page<CustomersEntity> findByCreatedByAndGroupNameAndDeletedAtIsNull(Long createdBy, String groupName, Pageable pageable);
    
    // ==================== SEARCH QUERIES (NON-PAGINATED) ====================
    
    /**
     * Search customers for ADMIN/SUPERADMIN (all customers)
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(:searchTerm IS NULL OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.gstNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(:groupName IS NULL OR c.groupName = :groupName) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:city IS NULL OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
           "(:state IS NULL OR LOWER(c.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
           "(:assignedTo IS NULL OR c.assignedTo = :assignedTo) AND " +
           "(:fromDate IS NULL OR c.createdAt >= :fromDate) AND " +
           "(:toDate IS NULL OR c.createdAt <= :toDate)")
    List<CustomersEntity> searchCustomers(
        @Param("searchTerm") String searchTerm,
        @Param("groupName") String groupName,
        @Param("status") String status,
        @Param("city") String city,
        @Param("state") String state,
        @Param("assignedTo") Long assignedTo,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );
    
    /**
     * Search customers for regular users (created by them OR assigned to them)
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(c.createdBy = :userId OR c.assignedTo = :userId) AND " +
           "(:searchTerm IS NULL OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.gstNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(:groupName IS NULL OR c.groupName = :groupName) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:city IS NULL OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
           "(:state IS NULL OR LOWER(c.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
           "(:fromDate IS NULL OR c.createdAt >= :fromDate) AND " +
           "(:toDate IS NULL OR c.createdAt <= :toDate)")
    List<CustomersEntity> searchCustomersForUser(
        @Param("userId") Long userId,
        @Param("searchTerm") String searchTerm,
        @Param("groupName") String groupName,
        @Param("status") String status,
        @Param("city") String city,
        @Param("state") String state,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate
    );
    
    // ==================== SEARCH QUERIES (PAGINATED) ====================
    
    /**
     * Search customers for ADMIN/SUPERADMIN with pagination
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(:searchTerm IS NULL OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.gstNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(:groupName IS NULL OR c.groupName = :groupName) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:city IS NULL OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
           "(:state IS NULL OR LOWER(c.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
           "(:assignedTo IS NULL OR c.assignedTo = :assignedTo) AND " +
           "(:fromDate IS NULL OR c.createdAt >= :fromDate) AND " +
           "(:toDate IS NULL OR c.createdAt <= :toDate)")
    Page<CustomersEntity> searchCustomersPaginated(
        @Param("searchTerm") String searchTerm,
        @Param("groupName") String groupName,
        @Param("status") String status,
        @Param("city") String city,
        @Param("state") String state,
        @Param("assignedTo") Long assignedTo,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
    /**
     * Search customers for regular users (created by them OR assigned to them) with pagination
     */
    @Query("SELECT c FROM CustomersEntity c WHERE " +
           "c.deletedAt IS NULL AND " +
           "(c.createdBy = :userId OR c.assignedTo = :userId) AND " +
           "(:searchTerm IS NULL OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.gstNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(:groupName IS NULL OR c.groupName = :groupName) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:city IS NULL OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
           "(:state IS NULL OR LOWER(c.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
           "(:fromDate IS NULL OR c.createdAt >= :fromDate) AND " +
           "(:toDate IS NULL OR c.createdAt <= :toDate)")
    Page<CustomersEntity> searchCustomersForUserPaginated(
        @Param("userId") Long userId,
        @Param("searchTerm") String searchTerm,
        @Param("groupName") String groupName,
        @Param("status") String status,
        @Param("city") String city,
        @Param("state") String state,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
 // ADD THESE NEW METHODS TO CustomersRepo.java

 // ==================== NEW METHODS FOR SUB-GROUP FILTERING ====================

 /**
  * Find by group name and sub-group name with pagination
  */
 Page<CustomersEntity> findByGroupNameAndSubGroupNameAndDeletedAtIsNull(
		 String groupName, 
     String subGroupName, 
     Pageable pageable
 );

 /**
  * Find by group name and sub-group name for specific user with pagination
  */
 @Query("SELECT c FROM CustomersEntity c WHERE " +
        "c.deletedAt IS NULL AND " +
        "(c.createdBy = :userId OR c.assignedTo = :userId) AND " +
        "c.groupName = :groupName AND " +
        "c.subGroupName = :subGroupName")
 Page<CustomersEntity> findByUserAndGroupNameAndSubGroupNameAndDeletedAtIsNull(
     @Param("userId") Long userId, 
     @Param("groupName") String groupName,
     @Param("subGroupName") String subGroupName,
     Pageable pageable
 );

 // ==================== UPDATED SEARCH QUERIES WITH SUB-GROUP SUPPORT ====================

 /**
  * Search customers for ADMIN/SUPERADMIN with pagination - UPDATED WITH SUB-GROUP
  */
 @Query("SELECT c FROM CustomersEntity c WHERE " +
        "c.deletedAt IS NULL AND " +
        "(:searchTerm IS NULL OR " +
        "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.gstNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
        "(:groupName IS NULL OR c.groupName = :groupName) AND " +
        "(:subGroupName IS NULL OR c.subGroupName = :subGroupName) AND " +
        "(:status IS NULL OR c.status = :status) AND " +
        "(:city IS NULL OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
        "(:state IS NULL OR LOWER(c.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
        "(:assignedTo IS NULL OR c.assignedTo = :assignedTo) AND " +
        "(:fromDate IS NULL OR c.createdAt >= :fromDate) AND " +
        "(:toDate IS NULL OR c.createdAt <= :toDate)")
 Page<CustomersEntity> searchCustomersPaginated(
     @Param("searchTerm") String searchTerm,
     @Param("groupName") String groupName,
     @Param("subGroupName") String subGroupName,
     @Param("status") String status,
     @Param("city") String city,
     @Param("state") String state,
     @Param("assignedTo") Long assignedTo,
     @Param("fromDate") LocalDateTime fromDate,
     @Param("toDate") LocalDateTime toDate,
     Pageable pageable
 );

 /**
  * Search customers for regular users with pagination - UPDATED WITH SUB-GROUP
  */
 @Query("SELECT c FROM CustomersEntity c WHERE " +
        "c.deletedAt IS NULL AND " +
        "(c.createdBy = :userId OR c.assignedTo = :userId) AND " +
        "(:searchTerm IS NULL OR " +
        "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
        "LOWER(c.gstNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
        "(:groupName IS NULL OR c.groupName = :groupName) AND " +
        "(:subGroupName IS NULL OR c.subGroupName = :subGroupName) AND " +
        "(:status IS NULL OR c.status = :status) AND " +
        "(:city IS NULL OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
        "(:state IS NULL OR LOWER(c.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
        "(:fromDate IS NULL OR c.createdAt >= :fromDate) AND " +
        "(:toDate IS NULL OR c.createdAt <= :toDate)")
 Page<CustomersEntity> searchCustomersForUserPaginated(
     @Param("userId") Long userId,
     @Param("searchTerm") String searchTerm,
     @Param("groupName") String groupName,
     @Param("subGroupName") String subGroupName,
     @Param("status") String status,
     @Param("city") String city,
     @Param("state") String state,
     @Param("fromDate") LocalDateTime fromDate,
     @Param("toDate") LocalDateTime toDate,
     Pageable pageable
 );
 /**
  * Find customer by project ID
  * FIXED: Changed from CustomerEntity to CustomersEntity (plural)
  * FIXED: Changed from ProjectEntity to DropdownProjectEntity
  */
 @Query("SELECT c FROM CustomersEntity c " +
        "INNER JOIN DropdownProjectEntity p ON c.customerCode = p.customerCode " +
        "WHERE p.projectUniqueId = :projectId AND c.deletedAt IS NULL")
 Optional<CustomersEntity> findByProjectId(@Param("projectId") String projectId);
 // ==================== INSTRUCTIONS ====================

 /*
  * WHAT TO DO:
  * 
  * 1. REMOVE THE OLD searchCustomersPaginated() METHOD (without subGroupName parameter)
  * 2. REMOVE THE OLD searchCustomersForUserPaginated() METHOD (without subGroupName parameter)
  * 3. ADD ALL THE NEW METHODS ABOVE to your CustomersRepo.java
  * 
  * These new methods add subGroupName parameter to the search queries and 
  * include new methods for filtering by both group and sub-group.
  */
    
}