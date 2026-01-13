package com.istlgroup.istl_group_crm_backend.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bill_payments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillPaymentEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id", nullable = false)
    private BillEntity bill;
    
    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;
    
    @Column(name = "payment_mode", length = 50)
    private String paymentMode; // Bank Transfer, UPI, Cheque, NEFT, RTGS, Cash
    
    @Column(name = "reference_number", length = 100)
    private String referenceNumber;
    
    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;
    
    @Column(name = "paid_by")
    private Long paidBy;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    // Transient field
    @Transient
    private String paidByName;
}

























































