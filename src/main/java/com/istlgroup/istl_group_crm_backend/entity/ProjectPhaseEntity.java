package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * One phase/stage of the weekly EPC execution plan (1:many under a project).
 * Week numbers are 1-based and relative to the project start. Explicit dates are
 * optional display overrides. Drives the timeline / Gantt in the Technical Scope tab.
 */
@Entity
@Table(name = "project_phases")
@Data
public class ProjectPhaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    @Column(name = "phase_name", nullable = false)
    private String phaseName;

    @Column(name = "phase_description", columnDefinition = "TEXT")
    private String phaseDescription;

    @Column(name = "start_week")
    private Integer startWeek;

    @Column(name = "end_week")
    private Integer endWeek;

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    private LocalDate plannedEndDate;

    @Column(name = "actual_start_date")
    private LocalDate actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDate actualEndDate;

    @Column(name = "status")
    private String status = "Not Started";

    @Column(name = "progress_percent", precision = 5, scale = 2)
    private BigDecimal progressPercent = BigDecimal.ZERO;

    /**
     * Planned % for this phase in SIMPLE tracking (Solar_Rooftop &lt; 100 kW), typed
     * directly in the grid instead of the weekly Weeks modal. In DETAILED tracking the
     * planned figure is derived from project_progress_periods and this stays null.
     */
    @Column(name = "planned_progress_pct", precision = 5, scale = 2)
    private BigDecimal plannedProgressPct;

    /**
     * Absolute project-level weight (%). A parent phase's weight equals the sum of its
     * sub-items' weights; all parent weights across a project sum to 100%. Mirrors the
     * EPC tracker "Weight (%)" column. Used to compute the weighted-average project progress.
     */
    @Column(name = "weight_pct", precision = 9, scale = 6)
    private BigDecimal weightPct;

    /**
     * Planned procurement budget (cost to procure) for this item, in project currency.
     * Stored only on leaf items (sub-items, or parents with no children). A parent with
     * children derives its budget as the sum of its sub-items' budgets, the same way
     * weightPct rolls up. Used by the Commercial tab's Budget Allocation block.
     */
    @Column(name = "planned_budget", precision = 18, scale = 2)
    private BigDecimal plannedBudget;

    /**
     * Sub-items / work-packages under this phase, stored as a JSON array.
     * Each element: { "name": "...", "status": "Not Started", "progressPercent": 0, "description": "" }
     * Serialised by the service layer; never mapped by JPA so no extra table is needed.
     */
    @Column(name = "sub_items", columnDefinition = "JSON")
    private String subItems;

    @Column(name = "responsible_user_id")
    private Long responsibleUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
