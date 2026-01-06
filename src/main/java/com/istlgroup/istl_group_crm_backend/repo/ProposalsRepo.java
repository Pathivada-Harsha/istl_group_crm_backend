package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
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
public interface ProposalsRepo extends JpaRepository<ProposalsEntity, Long> {
    
    /**
     * Count proposals by number prefix (for auto-generation)
     */
    long countByProposalNoStartingWith(String prefix);
    
    /**
     * Find by proposal number
     */
    Optional<ProposalsEntity> findByProposalNo(String proposalNo);
    
    /**
     * Find all active proposals (not deleted)
     */
    List<ProposalsEntity> findByDeletedAtIsNull();
    
    /**
     * Find all active proposals with pagination
     */
    Page<ProposalsEntity> findByDeletedAtIsNull(Pageable pageable);
    
    /**
     * Find proposals prepared by user OR where user created associated lead/customer
     * (For regular users - they see proposals they prepared)
     */
    @Query("SELECT p FROM ProposalsEntity p WHERE " +
           "p.deletedAt IS NULL AND " +
           "p.preparedBy = :userId")
    Page<ProposalsEntity> findByPreparedByAndDeletedAtIsNull(@Param("userId") Long userId, Pageable pageable);
    
    /**
     * Find by group name with pagination
     */
    @Query("SELECT p FROM ProposalsEntity p WHERE " +
           "p.deletedAt IS NULL AND " +
           "p.groupName = :groupName")
    Page<ProposalsEntity> findByGroupNameAndDeletedAtIsNull(@Param("groupName") String groupName, Pageable pageable);
    
    /**
     * Find by group and prepared by
     */
    @Query("SELECT p FROM ProposalsEntity p WHERE " +
           "p.deletedAt IS NULL AND " +
           "p.groupName = :groupName AND " +
           "p.preparedBy = :userId")
    Page<ProposalsEntity> findByGroupNameAndPreparedByAndDeletedAtIsNull(
        @Param("groupName") String groupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    /**
     * Search proposals for ADMIN/SUPERADMIN with pagination
     */
    @Query("SELECT p FROM ProposalsEntity p WHERE " +
           "p.deletedAt IS NULL AND " +
           "(:searchTerm IS NULL OR " +
           "LOWER(p.proposalNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(:status IS NULL OR p.status = :status) AND " +
           "(:groupName IS NULL OR p.groupName = :groupName) AND " +
           "(:preparedBy IS NULL OR p.preparedBy = :preparedBy) AND " +
           "(:leadId IS NULL OR p.leadId = :leadId) AND " +
           "(:customerId IS NULL OR p.customerId = :customerId) AND " +
           "(:fromDate IS NULL OR p.createdAt >= :fromDate) AND " +
           "(:toDate IS NULL OR p.createdAt <= :toDate)")
    Page<ProposalsEntity> searchProposalsPaginated(
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("groupName") String groupName,
        @Param("preparedBy") Long preparedBy,
        @Param("leadId") Long leadId,
        @Param("customerId") Long customerId,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
    /**
     * Search proposals for regular users (only prepared by them) with pagination
     */
    @Query("SELECT p FROM ProposalsEntity p WHERE " +
           "p.deletedAt IS NULL AND " +
           "p.preparedBy = :userId AND " +
           "(:searchTerm IS NULL OR " +
           "LOWER(p.proposalNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(:status IS NULL OR p.status = :status) AND " +
           "(:groupName IS NULL OR p.groupName = :groupName) AND " +
           "(:leadId IS NULL OR p.leadId = :leadId) AND " +
           "(:customerId IS NULL OR p.customerId = :customerId) AND " +
           "(:fromDate IS NULL OR p.createdAt >= :fromDate) AND " +
           "(:toDate IS NULL OR p.createdAt <= :toDate)")
    Page<ProposalsEntity> searchProposalsForUserPaginated(
        @Param("userId") Long userId,
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("groupName") String groupName,
        @Param("leadId") Long leadId,
        @Param("customerId") Long customerId,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
    /**
     * Find proposals by lead ID
     */
    List<ProposalsEntity> findByLeadIdAndDeletedAtIsNull(Long leadId);
    
    /**
     * Find proposals by customer ID
     */
    List<ProposalsEntity> findByCustomerIdAndDeletedAtIsNull(Long customerId);
}