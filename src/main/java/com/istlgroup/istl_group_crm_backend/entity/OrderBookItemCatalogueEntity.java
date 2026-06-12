package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(
    name = "orderbook_item_catalogue",
    uniqueConstraints = @UniqueConstraint(columnNames = "item_name")
)
@Data
public class OrderBookItemCatalogueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The lookup key — unique, case-insensitive via DB collation. */
    @Column(name = "item_name", nullable = false, length = 255)
    private String itemName;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "unit", length = 50)
    private String unit = "Nos";

    @Column(name = "unit_price", precision = 18, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;

    @Column(name = "discount_percent", precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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