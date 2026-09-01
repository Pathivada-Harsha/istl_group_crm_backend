package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;

public interface OrderBookRepo extends JpaRepository<OrderBookEntity, Long> {
    
    Optional<OrderBookEntity> findByOrderBookNo(String orderBookNo);
    
    List<OrderBookEntity> findByDeletedAtIsNull();
    
    Page<OrderBookEntity> findByDeletedAtIsNull(Pageable pageable);
    
    List<OrderBookEntity> findByCustomerIdAndDeletedAtIsNull(Long customerId);
    
    List<OrderBookEntity> findByProposalIdAndDeletedAtIsNull(Long proposalId);
    
    List<OrderBookEntity> findByGroupNameAndDeletedAtIsNull(String groupName);
    
    List<OrderBookEntity> findByStatusAndDeletedAtIsNull(String status);
    
    long countByOrderBookNoStartingWith(String prefix);
    
    @Query("SELECT o FROM OrderBookEntity o WHERE o.deletedAt IS NULL " +
           "AND (:searchTerm IS NULL OR " +
           "LOWER(o.orderBookNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(o.orderTitle) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(o.poNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND (" +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))))) " +
           "AND (:status IS NULL OR o.status = :status) " +
           "AND (:groupName IS NULL OR o.groupName = :groupName) " +
           "AND (:subGroupName IS NULL OR o.subGroupName = :subGroupName) " +
           "AND (:fromDate IS NULL OR o.orderDate >= :fromDate) " +
           "AND (:toDate IS NULL OR o.orderDate <= :toDate)")
    Page<OrderBookEntity> searchOrderBooks(
        @Param("searchTerm") String searchTerm,
        @Param("status") String status,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate,
        Pageable pageable
    );

	List<OrderBookEntity> findByProjectIdAndDeletedAtIsNull(String projectId);

    // ── L4: user-scoped ───────────────────────────────────────────────────────

    /** All order books created by this user OR whose customer was created by / assigned to this user. */
    @Query("SELECT DISTINCT o FROM OrderBookEntity o WHERE o.deletedAt IS NULL AND (" +
           "  o.createdBy = :userId OR " +
           "  EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND " +
           "    (c.createdBy = :userId OR c.assignedTo = :userId OR " +
           "     EXISTS (SELECT l FROM LeadsEntity l WHERE l.customerId = c.id AND l.closedByUserId = :userId)))" +
           ")")
    Page<OrderBookEntity> findByUserScopedAndDeletedAtIsNull(
        @Param("userId") Long userId, Pageable pageable);

    /** L4 search — same user scope + all filter params. */
    @Query("SELECT DISTINCT o FROM OrderBookEntity o WHERE o.deletedAt IS NULL AND (" +
           "  o.createdBy = :userId OR " +
           "  EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND " +
           "    (c.createdBy = :userId OR c.assignedTo = :userId OR " +
           "     EXISTS (SELECT l FROM LeadsEntity l WHERE l.customerId = c.id AND l.closedByUserId = :userId)))" +
           ") AND " +
           "(:searchTerm IS NULL OR LOWER(o.orderBookNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " LOWER(o.orderTitle) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " LOWER(o.poNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND (" +
           " LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))))) AND " +
           "(:status IS NULL OR o.status = :status) AND " +
           "(:groupName IS NULL OR o.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR o.subGroupName = :subGroupName) AND " +
           "(:fromDate IS NULL OR o.orderDate >= :fromDate) AND " +
           "(:toDate IS NULL OR o.orderDate <= :toDate)")
    Page<OrderBookEntity> searchOrderBooksForUser(
        @Param("userId")      Long userId,
        @Param("searchTerm")  String searchTerm,
        @Param("status")      String status,
        @Param("groupName")   String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("fromDate")    LocalDate fromDate,
        @Param("toDate")      LocalDate toDate,
        Pageable pageable);

    // ── L3: team-scoped ───────────────────────────────────────────────────────

    /** All order books created by any team member OR whose customer belongs to any team member. */
    @Query("SELECT DISTINCT o FROM OrderBookEntity o WHERE o.deletedAt IS NULL AND (" +
           "  o.createdBy IN :memberIds OR " +
           "  EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND " +
           "    (c.createdBy IN :memberIds OR c.assignedTo IN :memberIds OR " +
           "     EXISTS (SELECT l FROM LeadsEntity l WHERE l.customerId = c.id AND l.closedByUserId IN :memberIds)))" +
           ") ORDER BY o.createdAt DESC")
    Page<OrderBookEntity> findByTeamScopedAndDeletedAtIsNull(
        @Param("memberIds") List<Long> memberIds, Pageable pageable);

    /** L3 search — team scope + all filter params. */
    @Query("SELECT DISTINCT o FROM OrderBookEntity o WHERE o.deletedAt IS NULL AND (" +
           "  o.createdBy IN :memberIds OR " +
           "  EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND " +
           "    (c.createdBy IN :memberIds OR c.assignedTo IN :memberIds OR " +
           "     EXISTS (SELECT l FROM LeadsEntity l WHERE l.customerId = c.id AND l.closedByUserId IN :memberIds)))" +
           ") AND " +
           "(:searchTerm IS NULL OR LOWER(o.orderBookNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " LOWER(o.orderTitle) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " LOWER(o.poNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " EXISTS (SELECT c FROM CustomersEntity c WHERE c.id = o.customerId AND (" +
           " LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           " LOWER(c.companyName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))))) AND " +
           "(:status IS NULL OR o.status = :status) AND " +
           "(:groupName IS NULL OR o.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR o.subGroupName = :subGroupName) AND " +
           "(:fromDate IS NULL OR o.orderDate >= :fromDate) AND " +
           "(:toDate IS NULL OR o.orderDate <= :toDate)")
    Page<OrderBookEntity> searchOrderBooksForTeam(
        @Param("memberIds")    List<Long> memberIds,
        @Param("searchTerm")   String searchTerm,
        @Param("status")       String status,
        @Param("groupName")    String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("fromDate")     LocalDate fromDate,
        @Param("toDate")       LocalDate toDate,
        Pageable pageable);

    // ── Client Financials roll-up ────────────────────────────────────────────
    // (project_id, SUM(total_amount)) for ONE customer's live, non-cancelled
    // order books. Scalar projection on purpose: OrderBookEntity carries the
    // uploaded PO as a LONGBLOB, so loading the entities just to add up a
    // column would drag every one of that client's PO files through the heap.
    @Query("SELECT o.projectId, COALESCE(SUM(o.totalAmount), 0) FROM OrderBookEntity o "
         + "WHERE o.deletedAt IS NULL AND o.customerId = :customerId "
         + "AND o.projectId IS NOT NULL "
         + "AND (o.status IS NULL OR o.status <> 'Cancelled') "
         + "GROUP BY o.projectId")
    List<Object[]> sumAwardedValueByProjectForCustomer(@Param("customerId") Long customerId);
}
