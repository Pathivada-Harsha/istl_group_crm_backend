package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "project_expense_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectExpenseHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(name = "changed_by_name", length = 200)
    private String changedByName;

    @Column(name = "old_status", length = 50)
    private String oldStatus;

    @Column(name = "new_status", length = 50)
    private String newStatus;

    @Column(name = "old_amount", precision = 15, scale = 2)
    private BigDecimal oldAmount;

    @Column(name = "new_amount", precision = 15, scale = 2)
    private BigDecimal newAmount;

    @Column(name = "change_description", columnDefinition = "TEXT")
    private String changeDescription;

    @Column(name = "snapshot_json", columnDefinition = "JSON")
    private String snapshotJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum ReferenceType {
        EXPENSE, ADVANCE
    }
}