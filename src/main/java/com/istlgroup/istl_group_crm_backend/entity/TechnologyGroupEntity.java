// TechnologyGroupEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Project Details (Technology area) Group taxonomy — deliberately separate
 * from {@link DropdownGroupEntity} (the org-level Group/SubGroup filter data
 * used across Cost & Expense, Receipts, Vendor Payments, Inventory, etc.),
 * per the requirement that this new dropdown not share that stack.
 */
@Entity
@Table(name = "technology_groups")
@Data
public class TechnologyGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_name", nullable = false, unique = true, length = 100)
    private String groupName;

    @Column(name = "group_label", nullable = false, length = 200)
    private String groupLabel;

    @Column(name = "is_active")
    private Boolean isActive = true;
}
