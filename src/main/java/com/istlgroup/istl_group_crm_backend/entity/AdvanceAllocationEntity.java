// AdvanceAllocationEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "advance_allocations", indexes = {
    @Index(name = "idx_receipt_id", columnList = "receipt_id"),
    @Index(name = "idx_invoice_id", columnList = "invoice_id"),
    @Index(name = "idx_allocation_date", columnList = "allocation_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdvanceAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    @JsonBackReference
    private ReceiptEntity receipt;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "allocated_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(name = "allocation_date", nullable = false)
    private LocalDateTime allocationDate;

    @Column(name = "allocated_by")
    private Long allocatedBy;

    @PrePersist
    protected void onCreate() {
        if (allocationDate == null) {
            allocationDate = LocalDateTime.now();
        }
    }
}