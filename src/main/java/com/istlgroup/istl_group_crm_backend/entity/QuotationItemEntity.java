package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Quotation Item Entity - Line items in a quotation
 */
@Entity
@Table(name = "quotation_items", indexes = {
    @Index(name = "idx_quotation_id", columnList = "quotation_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationItemEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private QuotationEntity quotation;
    
    @Column(name = "line_no")
    private Integer lineNo;
    
    @Column(name = "item_name")
    private String itemName;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity;
    
    @Column(name = "unit_price", precision = 18, scale = 2)
    private BigDecimal unitPrice;
    
    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent;
    
    // line_total is a GENERATED column in DB - DO NOT insert/update
    // It's calculated as: quantity * unit_price
    @Column(name = "line_total", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal lineTotal;
    
    @Column(name = "delivery_lead_time", length = 100)
    private String deliveryLeadTime;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}