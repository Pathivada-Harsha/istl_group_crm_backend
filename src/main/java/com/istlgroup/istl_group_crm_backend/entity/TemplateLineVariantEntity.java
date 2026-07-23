package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The curated set of allowed makes (variants) attached to a single BOM template
 * line, plus which one is the default the Suggest engine seeds. Admin curates a
 * subset of the catalog item's variants per template line (e.g. 3 of the item's
 * 5 makes) and marks one default. Plain Long fks, house style.
 */
@Entity
@Table(name = "template_line_variants")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateLineVariantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId; // -> lead_bom_template_items.id

    @Column(name = "variant_id", nullable = false)
    private Long variantId; // -> bom_item_variants.id

    @Column(name = "is_default")
    private Boolean isDefault = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
