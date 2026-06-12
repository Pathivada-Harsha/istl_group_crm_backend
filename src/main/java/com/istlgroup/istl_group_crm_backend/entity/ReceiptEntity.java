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
@Table(name = "receipts", indexes = {
    @Index(name = "idx_receipt_no", columnList = "receipt_no"),
    @Index(name = "idx_customer_id", columnList = "customer_id"),
    @Index(name = "idx_receipt_type", columnList = "receipt_type"),
    @Index(name = "idx_receipt_date", columnList = "receipt_date"),
    @Index(name = "idx_project_id", columnList = "project_id"),
    @Index(name = "idx_invoice_id", columnList = "invoice_id"),
    @Index(name = "idx_created_by", columnList = "created_by")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_no", unique = true, nullable = false, length = 80)
    private String receiptNo;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Column(name = "receipt_type", nullable = false, length = 50)
    private String receiptType; // ADVANCE, INVOICE_PAYMENT

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "group_id", length = 225)
    private String groupId;

    @Column(name = "sub_group_id", length = 225)
    private String subGroupId;

    @Column(name = "company", length = 50)
    private String company;

    @Column(name = "invoice_id")
    private Long invoiceId; // NULL for advances

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "applied_amount", precision = 18, scale = 2)
    private BigDecimal appliedAmount;

    @Column(name = "unapplied_amount", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal unappliedAmount;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

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

    @Transient
    private String customerName;

    @Transient
    private String customerCompanyName;

    @OneToMany(mappedBy = "receipt", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference
    @Builder.Default
    private List<AdvanceAllocationEntity> allocations = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (receiptDate == null) {
            receiptDate = LocalDate.now();
        }
        if (appliedAmount == null) {
            appliedAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static class ReceiptType {
        public static final String ADVANCE = "ADVANCE";
        public static final String INVOICE_PAYMENT = "INVOICE_PAYMENT";
    }

    public static class Company {
        public static final String ISTL = "ISTL";
        public static final String SESOLA = "SESOLA";
    }
}