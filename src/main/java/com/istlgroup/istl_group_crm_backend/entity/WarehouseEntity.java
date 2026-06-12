package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Warehouse / Store / Site-Store master.
 * Used as the page-level scope on the Inventory Management page and managed
 * via the "Warehouses" tab on the Dropdown Management page.
 *
 * group_name / sub_group_name are optional — when both are null the
 * warehouse is "global" and visible under any group/sub-group filter.
 * Warehouses are deliberately NOT scoped by project.
 *
 * Soft-delete convention (matches groups / projects): the first DELETE flips
 * `isActive` to false; a second DELETE on an already-inactive row hard-deletes.
 */
@Entity
@Table(
    name = "warehouses",
    indexes = {
        @Index(name = "idx_warehouse_group",     columnList = "group_name, sub_group_name"),
        @Index(name = "idx_warehouse_is_active", columnList = "is_active")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "in_charge", length = 150)
    private String inCharge;

    @Column(name = "phone", length = 20)
    private String phone;

    // Optional scoping — null on both means global.
    // Warehouses are deliberately NOT scoped by project.
    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}