package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One recorded change to a project BOM line.
 *
 * <p>Amending the BOM is how a buyer unblocks a purchase order that breached it, so
 * the pressure to quietly raise a quantity sits exactly where the money is. Every
 * amendment is therefore recorded: who changed which line, when, and from what to what.
 *
 * <p>{@code itemName} is denormalised so the log stays readable after the line it
 * refers to has been soft-deleted.
 */
@Entity
@Table(name = "project_bom_audit")
@Data
public class ProjectBomAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code project_bom.id}. Null when the line was removed. */
    @Column(name = "project_bom_id")
    private Long projectBomId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "item_name", length = 300)
    private String itemName;

    /** quantity | unit_rate | ADDED | REMOVED */
    @Column(name = "field", nullable = false, length = 40)
    private String field;

    @Column(name = "old_value", length = 120)
    private String oldValue;

    @Column(name = "new_value", length = 120)
    private String newValue;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        if (this.changedAt == null) this.changedAt = LocalDateTime.now();
    }
}
