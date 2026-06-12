package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_history", indexes = {
    @Index(name = "idx_invoice_id", columnList = "invoice_id"),
    @Index(name = "idx_payment_date", columnList = "payment_date"),
    @Index(name = "idx_receipt_id", columnList = "receipt_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentHistoryEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Option 1: Keep the relationship and add insertable/updatable = false
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonBackReference
    private InvoiceEntity invoice;
    
    // Add this to access invoice_id directly when needed
    @Column(name = "invoice_id", insertable = false, updatable = false)
    private Long invoiceId;
    
    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;
    
    @Column(name = "receipt_id")
    private Long receiptId;
    
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;
    
    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;
    
    @Column(name = "recorded_by")
    private Long recordedBy;
    
    @PrePersist
    protected void onCreate() {
        if (paymentDate == null) {
            paymentDate = LocalDateTime.now();
        }
    }
}