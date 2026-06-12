package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vendor_advances", indexes = {
    @Index(name = "idx_va_vendor_id",    columnList = "vendor_id"),
    @Index(name = "idx_va_advance_date", columnList = "advance_date"),
    @Index(name = "idx_va_payment_type", columnList = "payment_type"),
    @Index(name = "idx_va_project_id",   columnList = "project_id"),
    @Index(name = "idx_va_bill_id",      columnList = "bill_id"),
    @Index(name = "idx_va_created_by",   columnList = "created_by")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorAdvanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "advance_no", unique = true, nullable = false, length = 80)
    private String advanceNo;

    @Column(name = "advance_date", nullable = false)
    private LocalDate advanceDate;

    /** ADVANCE = pre-payment; BILL_PAYMENT = paid against a specific bill */
    @Column(name = "payment_type", nullable = false, length = 50)
    private String paymentType;

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    /** Null for ADVANCE type; set for BILL_PAYMENT type */
    @Column(name = "bill_id")
    private Long billId;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "group_id", length = 225)
    private String groupId;

    @Column(name = "sub_group_id", length = 225)
    private String subGroupId;

    @Column(name = "company", length = 50)
    private String company;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "applied_amount", precision = 18, scale = 2)
    private BigDecimal appliedAmount;

    @Column(name = "unapplied_amount", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal unappliedAmount;

    @Column(name = "payment_mode", length = 50)
    private String paymentMode;

    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "advance", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference
    @Builder.Default
    private List<VendorAdvanceAllocationEntity> allocations = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (advanceDate == null) advanceDate = LocalDate.now();
        if (appliedAmount == null) appliedAmount = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static final class PaymentType {
        public static final String ADVANCE      = "ADVANCE";
        public static final String BILL_PAYMENT = "BILL_PAYMENT";
    }
}