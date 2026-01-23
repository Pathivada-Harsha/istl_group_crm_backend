// PaymentHistoryRepository.java
package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentHistoryRepository extends JpaRepository<PaymentHistoryEntity, Long> {
    
    @Query("SELECT ph FROM PaymentHistoryEntity ph WHERE ph.invoice.id = :invoiceId ORDER BY ph.paymentDate DESC")
    List<PaymentHistoryEntity> findByInvoiceIdOrderByPaymentDateDesc(@Param("invoiceId") Long invoiceId);
    
    
    /**
     * Get payment method distribution by project
     * FIXED: Use 'invoice' (the relationship field) instead of 'invoiceId'
     */
    @Query("SELECT ph.paymentMethod, SUM(ph.amount), COUNT(ph) " +
           "FROM PaymentHistoryEntity ph " +
           "WHERE ph.invoice.projectId = :projectId " +
           "AND ph.invoice.deletedAt IS NULL " +
           "GROUP BY ph.paymentMethod " +
           "ORDER BY SUM(ph.amount) DESC")
    List<Object[]> getPaymentMethodDistributionByProject(@Param("projectId") String projectId);
    
    /**
     * Get monthly payments by project
     * FIXED: Use native SQL query for DATE_FORMAT
     */
    @Query(value = "SELECT DATE_FORMAT(ph.payment_date, '%Y-%m') as month, " +
                   "SUM(ph.amount) as total_amount, " +
                   "COUNT(ph.id) as payment_count " +
                   "FROM payment_history ph " +
                   "JOIN invoices inv ON ph.invoice_id = inv.id " +
                   "WHERE inv.project_id = :projectId " +
                   "AND inv.deleted_at IS NULL " +
                   "GROUP BY DATE_FORMAT(ph.payment_date, '%Y-%m') " +
                   "ORDER BY month DESC " +
                   "LIMIT 12", 
           nativeQuery = true)
    List<Object[]> getMonthlyPaymentsByProject(@Param("projectId") String projectId);
    
    /**
     * Get recent payments by project (top 10)
     * FIXED: Use 'invoice' relationship
     */
    @Query("SELECT ph FROM PaymentHistoryEntity ph " +
           "WHERE ph.invoice.projectId = :projectId " +
           "AND ph.invoice.deletedAt IS NULL " +
           "ORDER BY ph.paymentDate DESC")
    List<PaymentHistoryEntity> findTop10ByProjectIdOrderByPaymentDateDesc(@Param("projectId") String projectId);
}