// InvoiceEntity.java
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
@Table(name = "invoices", indexes = {
    @Index(name = "idx_invoice_no", columnList = "invoice_no"),
    @Index(name = "idx_customer_id", columnList = "customer_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_project_id", columnList = "project_id"),
    @Index(name = "idx_group_id", columnList = "group_id"),
    @Index(name = "idx_sub_group_id", columnList = "sub_group_id"),
    @Index(name = "idx_invoice_date", columnList = "invoice_date"),
    @Index(name = "idx_due_date", columnList = "due_date"),
    @Index(name = "idx_created_by", columnList = "created_by")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", unique = true, length = 80)
    private String invoiceNo;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "group_id", length = 225)
    private String groupId;

    @Column(name = "sub_group_id", length = 225)
    private String subGroupId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", precision = 18, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "balance_amount", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal balanceAmount;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // Relationships
    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference  // ← ADD THIS
    @Builder.Default
    private List<InvoiceItemEntity> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (invoiceDate == null) {
            invoiceDate = LocalDate.now();
        }
        if (status == null || status.isEmpty()) {  // FIXED TYPO
            status = "Draft";
        }
        if (paidAmount == null) {
            paidAmount = BigDecimal.ZERO;
        }
    }
 // Add this to InvoiceEntity.java

    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference
    @Builder.Default
    private List<PaymentHistoryEntity> paymentHistory = new ArrayList<>();
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Status constants for reference
    public static class Status {
        public static final String DRAFT = "Draft";
        public static final String SENT = "Sent";
        public static final String PARTIALLY_PAID = "Partially Paid";
        public static final String PAID = "Paid";
        public static final String CANCELLED = "Cancelled";
    }
}