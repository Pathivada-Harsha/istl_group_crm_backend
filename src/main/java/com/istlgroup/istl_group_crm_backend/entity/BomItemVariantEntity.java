package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A selectable make/model ("variant") of a bom_items_master item. One catalog
 * item can carry several variants (e.g. PV Module -> Waaree / Adani / Premier);
 * the estimator picks one at quote time instead of retyping the make.
 *
 * Item-level fields (category, unit, HSN, tax) stay on bom_items_master and are
 * deliberately NOT duplicated here. The parent link is a plain Long fk
 * (bom_item_id), matching the house style used across the lead/BOM tables.
 */
@Entity
@Table(name = "bom_item_variants")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BomItemVariantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bom_item_id", nullable = false)
    private Long bomItemId; // -> bom_items_master.id

    @Column(length = 255)
    private String make;

    @Column(length = 255)
    private String model;

    @Column(columnDefinition = "TEXT")
    private String description; // spec text for this variant

    // Values for the parent item's variant_attributes schema, as a JSON object
    // keyed by field key: {"wattage":"540","cell_type":"Mono PERC"}. Null when the
    // parent item has no schema. Validated against that schema in the service.
    @Column(name = "attribute_values", columnDefinition = "JSON")
    private String attributeValues;

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
