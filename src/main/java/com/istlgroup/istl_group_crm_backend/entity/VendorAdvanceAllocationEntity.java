package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "vendor_advance_allocations", indexes = {
    @Index(name = "idx_vaa_advance_id",      columnList = "advance_id"),
    @Index(name = "idx_vaa_bill_id",         columnList = "bill_id"),
    @Index(name = "idx_vaa_allocation_date", columnList = "allocation_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorAdvanceAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advance_id", nullable = false)
    @JsonBackReference
    private VendorAdvanceEntity advance;

    @Column(name = "bill_id", nullable = false)
    private Long billId;

    @Column(name = "allocated_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(name = "allocation_date", nullable = false)
    private LocalDateTime allocationDate;

    @Column(name = "allocated_by")
    private Long allocatedBy;

    @PrePersist
    protected void onCreate() {
        if (allocationDate == null) allocationDate = LocalDateTime.now();
    }
}