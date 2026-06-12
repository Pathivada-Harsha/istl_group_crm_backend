package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Inventory item — a stock-keeping unit held at a specific warehouse.
 *
 * The pair (warehouse_id, item_code) is unique: the same SKU can exist
 * in multiple warehouses, each with its own running quantity.
 *
 * Stock status is NOT stored; it's derived at read time in the wrapper
 * from current_qty vs min_qty so it can never drift from the truth.
 */
@Entity
@Table(
    name = "inventory_items",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_inv_item_wh_code", columnNames = {"warehouse_id", "item_code"})
    },
    indexes = {
        @Index(name = "idx_inv_item_warehouse", columnList = "warehouse_id"),
        @Index(name = "idx_inv_item_project",   columnList = "project_id"),
        @Index(name = "idx_inv_item_category",  columnList = "category"),
        @Index(name = "idx_inv_item_is_active", columnList = "is_active")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to warehouses.id — required. */
    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "current_qty", nullable = false, precision = 15, scale = 3)
    private BigDecimal currentQty = BigDecimal.ZERO;

    @Column(name = "min_qty", nullable = false, precision = 15, scale = 3)
    private BigDecimal minQty = BigDecimal.ZERO;

    @Column(name = "max_qty", nullable = false, precision = 15, scale = 3)
    private BigDecimal maxQty = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitCost = BigDecimal.ZERO;

    // Optional scoping — null = no association
    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Item image stored as base64-encoded string.
     * Column allows up to ~10 MB; frontend enforces a 5 MB upload cap.
     */
    @Column(name = "image_data", columnDefinition = "LONGTEXT")
    private String imageData;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}