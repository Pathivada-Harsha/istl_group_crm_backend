// InfrastructureCategoryEntity.java
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
 * One category belonging to an {@link InfrastructureMasterListVersionEntity}
 * snapshot (e.g. "Transport and Logistics"). Looked up by {@code versionId}
 * (not a JPA relationship), mirroring {@link TechnologySubGroupEntity}'s
 * lookup-by-value convention. Rows are never mutated after insert — each
 * version keeps its own full category list for audit/history.
 */
@Entity
@Table(name = "infrastructure_category")
@Data
public class InfrastructureCategoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
