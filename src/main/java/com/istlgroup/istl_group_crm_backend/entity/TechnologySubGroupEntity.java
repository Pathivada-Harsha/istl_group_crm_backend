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

    @Column(name = "sub_group_name", nullable = false, length = 100)
    private String subGroupName;

    @Column(name = "sub_group_label", nullable = false, length = 200)
    private String subGroupLabel;

    @Column(name = "is_active")
    private Boolean isActive = true;
}
