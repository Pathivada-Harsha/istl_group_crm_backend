package com.istlgroup.istl_group_crm_backend.entity;


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
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;
    
    @Column(name = "unit_price", precision = 18, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;
    
    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;
    
    // Generated column - automatically calculated by database
    @Formula("(quantity * unit_price)")
    @Column(name = "line_total", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal lineTotal;
    
    // Calculate tax amount
    public BigDecimal getTaxAmount() {
        BigDecimal subtotal = quantity.multiply(unitPrice);
        return subtotal.multiply(taxPercent).divide(BigDecimal.valueOf(100));
    }
    
    // Calculate total with tax
    public BigDecimal getTotalWithTax() {
        BigDecimal subtotal = quantity.multiply(unitPrice);
        return subtotal.add(getTaxAmount());
    }
}
