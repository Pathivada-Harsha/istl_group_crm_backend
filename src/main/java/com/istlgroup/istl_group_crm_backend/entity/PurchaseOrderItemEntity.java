package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Purchase Order Item Entity - Line items in a PO with delivery tracking
 */
@Entity
@Table(name = "purchase_order_items", indexes = {
    @Index(name = "idx_po_id", columnList = "po_id"),
    @Index(name = "idx_item_sku", columnList = "item_sku"),
    @Index(name = "idx_po_id_line_no", columnList = "po_id, line_no")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderItemEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "po_id", nullable = false)
    @JsonBackReference  // Prevents circular reference in JSON
    private PurchaseOrderEntity purchaseOrder;
    
    @Column(name = "line_no")
    private Integer lineNo;
    
    @Column(name = "item_sku", length = 120)
    private String itemSku;
    
    @Column(name = "item_name")
    private String itemName;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "quantity", precision = 18, scale = 4, nullable = false)
    private BigDecimal quantity;
    
    @Column(name = "delivered_qty", precision = 18, scale = 4, nullable = false)
    private BigDecimal deliveredQty;
    
    @Column(name = "pending_qty", precision = 18, scale = 4, insertable = false, updatable = false)
    private BigDecimal pendingQty;
    
    @Column(name = "unit_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal unitPrice;
    
    @Column(name = "tax_percent", precision = 5, scale = 2, nullable = false)
    private BigDecimal taxPercent;
    
    @Column(name = "line_total", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal lineTotal;
    
    @Column(name = "delivery_schedule")
    private String deliverySchedule;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (deliveredQty == null) deliveredQty = BigDecimal.ZERO;
        // pending_qty and line_total are calculated by database
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // pending_qty and line_total are calculated by database
    }
}