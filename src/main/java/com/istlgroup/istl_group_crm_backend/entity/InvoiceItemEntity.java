// InvoiceItemEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "invoice_items", indexes = {
    @Index(name = "idx_invoice_id", columnList = "invoice_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonBackReference  // ← ADD THIS
    private InvoiceEntity invoice;

 // NEW: Link to order book item
    @Column(name = "order_book_item_id")
    private Long orderBookItemId;
    
    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 18, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent;

    @Column(name = "line_total", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal lineTotal;

    @Column(name = "unit_type", length = 20)
    private String unitType; // "Nos", "Kgs", "Boxes", "Pcs", etc.
}