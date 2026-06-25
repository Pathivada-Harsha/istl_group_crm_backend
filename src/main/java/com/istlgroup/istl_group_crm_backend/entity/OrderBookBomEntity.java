package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A single Bill of Materials / Bill of Quantities line for an order book.
 * amount is normally quantity * unitRate but is persisted as supplied so that
 * lump-sum lines (quantity/rate left blank) still work.
 */
@Entity
@Table(name = "order_book_bom")
@Data
public class OrderBookBomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_book_id", nullable = false)
    private Long orderBookId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    @Column(name = "category")
    private String category;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "make")
    private String make;

    @Column(name = "unit")
    private String unit;

    @Column(name = "quantity", precision = 18, scale = 3, nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_rate", precision = 18, scale = 2, nullable = false)
    private BigDecimal unitRate = BigDecimal.ZERO;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}