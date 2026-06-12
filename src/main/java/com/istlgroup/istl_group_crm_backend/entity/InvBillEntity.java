package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Vendor bill against an inventory Purchase Order.
 * Stored in inv_bills — separate from CRM bills table.
 */
@Entity
@Table(
    name = "inv_bills",
    indexes = {
        @Index(name = "idx_inv_bill_no",        columnList = "bill_no"),
        @Index(name = "idx_inv_bill_vendor",     columnList = "vendor_id"),
        @Index(name = "idx_inv_bill_po",         columnList = "po_id"),
        @Index(name = "idx_inv_bill_warehouse",  columnList = "warehouse_id"),
        @Index(name = "idx_inv_bill_project",    columnList = "project_id"),
        @Index(name = "idx_inv_bill_status",     columnList = "status")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvBillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Auto-generated: INV-BILL-YYYY-NNNNN */
    @Column(name = "bill_no", unique = true, length = 80)
    private String billNo;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "vendor_name", length = 255)
    private String vendorName;

    /** Optional link to an inv_purchase_orders row. */
    @Column(name = "po_id")
    private Long poId;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "bill_date")
    private LocalDate billDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "total_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * UNPAID → PARTIAL → PAID | OVERDUE
     * Recalculated on every payment via recalculateStatus().
     */
    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "UNPAID";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(
        mappedBy = "bill",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @Builder.Default
    private List<InvBillItemEntity> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (billDate == null) billDate = LocalDate.now();
    }

    /** Recomputes status from paidAmount vs totalAmount. */
    public void recalculateStatus() {
        BigDecimal paid  = paidAmount  != null ? paidAmount  : BigDecimal.ZERO;
        BigDecimal total = totalAmount != null ? totalAmount : BigDecimal.ZERO;

        if (paid.compareTo(BigDecimal.ZERO) == 0) {
            boolean overdue = dueDate != null && dueDate.isBefore(LocalDate.now());
            this.status = overdue ? "OVERDUE" : "UNPAID";
        } else if (paid.compareTo(total) >= 0) {
            this.status = "PAID";
        } else {
            this.status = "PARTIAL";
        }
        this.paidAmount = paid;
    }

    @Transient
    public BigDecimal getBalanceAmount() {
        BigDecimal total = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        BigDecimal paid  = paidAmount  != null ? paidAmount  : BigDecimal.ZERO;
        return total.subtract(paid);
    }
}