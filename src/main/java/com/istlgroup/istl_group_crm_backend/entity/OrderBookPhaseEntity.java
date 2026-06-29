package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * One phase/stage of the weekly EPC execution plan (1:many under an order book).
 * Week numbers are 1-based and relative to the project start. Explicit dates are
 * optional display overrides. Drives the timeline / Gantt in the Technical Scope tab.
 */
@Entity
@Table(name = "order_book_phases")
@Data
public class OrderBookPhaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_book_id", nullable = false)
    private Long orderBookId;

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