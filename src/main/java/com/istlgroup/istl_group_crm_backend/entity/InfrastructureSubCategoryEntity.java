// InfrastructureSubCategoryEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One sub-sector belonging to an {@link InfrastructureCategoryEntity} (e.g.
 * "Roads and bridges" under "Transport and Logistics"). Looked up by
 * {@code categoryId} (not a JPA relationship), same convention as
 * {@link InfrastructureCategoryEntity#getVersionId()}. Rows are never
 * mutated after insert.
 */
@Entity
@Table(name = "infrastructure_sub_category")
@Data
public class InfrastructureSubCategoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
