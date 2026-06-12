package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Payment made against an inventory vendor bill.
 * Stored in inv_payments — separate from CRM bill_payments table.
 */
@Entity
@Table(
    name = "inv_payments",
    indexes = {
        @Index(name = "idx_inv_pay_no",      columnList = "payment_no"),
        @Index(name = "idx_inv_pay_bill",    columnList = "bill_id"),
        @Index(name = "idx_inv_pay_vendor",  columnList = "vendor_id"),
        @Index(name = "idx_inv_pay_project", columnList = "project_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvPaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Auto-generated: INV-PAY-YYYY-NNNNN */
    @Column(name = "payment_no", unique = true, length = 80)
    private String paymentNo;

    /** FK to inv_bills.id — required for bill-linked payments. */
    @Column(name = "bill_id")
    private Long billId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "vendor_name", length = 255)
    private String vendorName;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    /** Bank Transfer, UPI, NEFT, RTGS, Cheque, Cash */
    @Column(name = "payment_mode", length = 60)
    private String paymentMode;

    @Column(name = "reference_number", length = 150)
    private String referenceNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * For ADVANCE payments: tracks how much has been allocated to bills.
     * unappliedAmount = amount - appliedAmount (computed in service/wrapper).
     */
    @Column(name = "applied_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal appliedAmount = BigDecimal.ZERO;

    /**
     * For BILL_PAYMENT allocations created from an advance:
     * FK back to the advance payment that funded this allocation.
     */
    @Column(name = "advance_id")
    private Long advanceId;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (paymentDate == null) paymentDate = LocalDate.now();
    }
}