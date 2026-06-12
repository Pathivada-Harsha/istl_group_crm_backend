package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "proposal_items")
@Data
public class ProposalItemEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;
    
    @Column(name = "line_no")
    private Integer lineNo = 1;
    
    @Column(name = "item_name")
    private String itemName;
    
    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;
    
    @Column(name = "unit")
    private String unit = "Nos";
    
    @Column(name = "unit_price", precision = 18, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;
    
    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Transient
    private BigDecimal lineTotal;
    
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}