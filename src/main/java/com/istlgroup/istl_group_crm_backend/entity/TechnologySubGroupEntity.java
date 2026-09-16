// TechnologySubGroupEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Sub Group belonging to a {@link TechnologyGroupEntity}. Looked up by the
 * parent group's name (not a JPA relationship) — mirrors how the existing
 * subgroup lookup in {@code DropdownFilterService} works, kept simple since
 * this taxonomy has no admin UI of its own yet.
 */
@Entity
@Table(name = "technology_sub_groups")
@Data
public class TechnologySubGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    // 200, not 100 — matches InfrastructureSubCategoryEntity.name's own
    // length, since this column is upserted directly from that same parsed
    // sub-category name (see InfrastructureMasterListVersionWriter
    // .upsertLiveSubGroups). This is what caused a real 19-Sep-2025 Harmonized
    // Master List sub-sector name to fail with "Data too long for column
    // 'sub_group_name'" — the audit-snapshot table already accepted it fine.
    @Column(name = "sub_group_name", nullable = false, length = 200)
    private String subGroupName;

    @Column(name = "sub_group_label", nullable = false, length = 200)
    private String subGroupLabel;

    @Column(name = "is_active")
    private Boolean isActive = true;
}
