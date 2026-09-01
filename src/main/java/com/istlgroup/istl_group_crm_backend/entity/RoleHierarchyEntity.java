package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "role_hierarchy")
public class RoleHierarchyEntity {

    @Id
    @Column(name = "role_name", length = 100)
    private String roleName;

    @Column(name = "level_order", nullable = false)
    private Integer levelOrder;

    /**
     * Which lead-ownership slot this role claims for the leads its members CREATE.
     *   NULL      → no self-ownership; the normal routing rules apply
     *   'BD'      → the creator goes in leads.bd_assigned_to, no lead handler
     *   'HANDLER' → the creator goes in leads.assigned_to, no BD
     *
     * Data-driven on purpose: switching a role on or off is a one-line UPDATE, no code
     * change and no redeploy. See LeadsService.createLead.
     *
     * Set by SQL only. The role-hierarchy CRUD (saveHierarchy / updateHierarchy) copies
     * only its own named fields onto the managed entity and deliberately leaves this one
     * alone, so an edit from the Roles screen — which does not send this field — cannot
     * wipe it.
     */
    @Column(name = "lead_self_assign_slot", length = 20)
    private String leadSelfAssignSlot;

    @Column(name = "can_assign_roles", columnDefinition = "JSON", nullable = false)
    private String canAssignRoles;   // stored as JSON string e.g. ["ADMIN","TELECALLER"]

    @Column(name = "can_see_roles", columnDefinition = "JSON", nullable = false)
    private String canSeeRoles;      // stored as JSON string

    @Column(name = "description")
    private String description;

    // BUG FIX: created_at is auto-set by DB default, never touched by app
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // BUG FIX: updated_at was insertable=false which prevented Hibernate from
    // writing it on UPDATE, causing "Column 'updated_at' cannot be null".
    // We now manage it manually in the service layer.
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}