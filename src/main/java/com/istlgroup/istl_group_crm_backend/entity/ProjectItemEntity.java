package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "project_items")
@Data
public class ProjectItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "line_no")
    private Integer lineNo = 1;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "proposal_item_id")
    private Long proposalItemId;

    @Column(name = "quantity", precision = 18, scale = 6)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit")
    private String unit = "Nos";

    @Column(name = "unit_price", precision = 18, scale = 6)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;

    @Column(name = "discount_percent", precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(name = "item_remarks", columnDefinition = "TEXT")
    private String itemRemarks;

    // Tracks how much of this item has been invoiced across all invoices
    @Column(name = "invoiced_qty", precision = 18, scale = 6)
    private BigDecimal invoicedQty = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // STORED generated columns — DB computes these automatically.
    // insertable=false, updatable=false so JPA never tries to write them.
    @Column(name = "line_subtotal", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal lineSubtotal;

    @Column(name = "discount_amount", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "taxable_amount", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal taxableAmount;

    @Column(name = "tax_amount", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal taxAmount;

    @Column(name = "line_total", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal lineTotal;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
