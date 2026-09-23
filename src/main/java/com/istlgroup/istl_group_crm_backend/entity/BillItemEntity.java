package com.istlgroup.istl_group_crm_backend.entity;


import com.istlgroup.istl_group_crm_backend.util.MoneyRounding;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Formula;

import java.math.BigDecimal;

@Entity
@Table(name = "bill_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillItemEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id", nullable = false)
    private BillEntity bill;
    
    @Column(name = "po_item_id")
    private Long poItemId;

    @Column(name = "item_name", length = 255)
    private String itemName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "quantity", precision = 18, scale = 6)
    private BigDecimal quantity = BigDecimal.ONE;
    
    @Column(name = "unit_price", precision = 18, scale = 6)
    private BigDecimal unitPrice = BigDecimal.ZERO;
    
    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;
    
    // Generated column - automatically calculated by database
    @Formula("(quantity * unit_price)")
    @Column(name = "line_total", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal lineTotal;
    
    // Calculate tax amount
    public BigDecimal getTaxAmount() {
        // percentOf, not a bare divide(BigDecimal.valueOf(100)) — see the note in
        // MoneyRounding.percentOf. The null guards matter too: the fields default
        // to ONE/ZERO but are settable to null, and this used to NPE on either.
        return MoneyRounding.percentOf(getSubtotal(), taxPercent);
    }

    /** Quantity x unit price, to the paisa. */
    public BigDecimal getSubtotal() {
        return MoneyRounding.money((quantity == null ? BigDecimal.ZERO : quantity)
                .multiply(unitPrice == null ? BigDecimal.ZERO : unitPrice));
    }
    
    // Calculate total with tax
    public BigDecimal getTotalWithTax() {
        return getSubtotal().add(getTaxAmount());
    }
}