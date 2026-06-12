package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Tracks the last-assigned telecaller per group+subgroup scope
 * so that round-robin assignment persists across server restarts
 * and is safe in multi-instance deployments.
 *
 * One row per (groupName, subGroupName) combination.
 * When groupName/subGroupName are null we use the literal "GLOBAL".
 */
@Entity
@Table(name = "telecaller_assignment_tracker",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_tracker_group_subgroup",
           columnNames = {"group_name", "sub_group_name"}))
@Data
public class TelecallerAssignmentTracker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Scope key — "GLOBAL" when no group is set */
    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    /** Scope key — "GLOBAL" when no subgroup is set */
    @Column(name = "sub_group_name", nullable = false, length = 100)
    private String subGroupName;

    /**
     * The user-id of the telecaller who was most recently assigned a lead
     * in this scope. The next assignment will be the telecaller that comes
     * after this one in the sorted active-telecaller list.
     */
    @Column(name = "last_assigned_user_id")
    private Long lastAssignedUserId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }
}