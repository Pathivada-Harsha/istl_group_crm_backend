package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Append-only audit-trail entry for every workflow action (Go/No-Go, doc
 * requests, CFO/MD approvals, submission). Rendered in the Workflow tab's sticky
 * audit sidebar.
 */
@Entity
@Table(name = "tender_approval_log")
@Data
public class TenderApprovalLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "stage")
    private String stage;

    @Column(name = "action", columnDefinition = "TEXT")
    private String action;

    @Column(name = "action_by")
    private String actionBy;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
