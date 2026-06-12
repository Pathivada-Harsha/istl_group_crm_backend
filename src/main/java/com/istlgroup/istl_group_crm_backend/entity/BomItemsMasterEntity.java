package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bom_items_master")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BomItemsMasterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String category; // CCMS, ITMS, MCMS, EPC, COMMON

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String specification;

    @Column(name = "default_unit", length = 50)
    private String defaultUnit = "Nos";

    @Column(name = "default_tax_percent", precision = 5, scale = 2)
    private BigDecimal defaultTaxPercent = BigDecimal.valueOf(18.00);

    @Column(name = "make_brand")
    private String makeBrand;

    @Column(name = "hsn_code", length = 50)
    private String hsnCode;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}