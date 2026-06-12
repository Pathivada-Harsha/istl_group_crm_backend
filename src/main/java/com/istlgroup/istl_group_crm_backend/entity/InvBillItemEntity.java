package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
    name = "inv_bill_items",
    indexes = {
        @Index(name = "idx_inv_bi_bill", columnList = "bill_id"),
        @Index(name = "idx_inv_bi_item", columnList = "inventory_item_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvBillItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id", nullable = false)
    @JsonBackReference
    private InvBillEntity bill;

    @Column(name = "inventory_item_id")
    private Long inventoryItemId;

    @Column(name = "item_code", length = 80)
    private String itemCode;

    @Column(name = "item_name", length = 255)
    private String itemName;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "qty", precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal qty = BigDecimal.ZERO;

    @Column(name = "rate", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal rate = BigDecimal.ZERO;

    @Column(name = "tax_pct", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxPct = BigDecimal.ZERO;

    @Column(name = "notes", length = 500)
    private String notes;
}