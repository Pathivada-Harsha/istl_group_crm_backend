package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Technical Scope header for an order book (1:1).
 * Phases (OrderBookPhaseEntity) reference order_book_id directly.
 */
@Entity
@Table(name = "order_book_scope")
@Data
public class OrderBookScopeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_book_id", nullable = false, unique = true)
    private Long orderBookId;

    // project_id intentionally NOT stored here — it is reachable through the
    // parent order_book row. Duplicating it caused staleness if the order
    // book's project link ever changed.

    @Column(name = "project_type")
    private String projectType;

    @Column(name = "scope_of_work", columnDefinition = "TEXT")
    private String scopeOfWork;

    @Column(name = "technical_notes", columnDefinition = "TEXT")
    private String technicalNotes;

    @Column(name = "system_capacity")
    private String systemCapacity;

    @Column(name = "site_location")
    private String siteLocation;

    @Column(name = "site_lat", precision = 10, scale = 7)
    private java.math.BigDecimal siteLat;

    @Column(name = "site_lng", precision = 10, scale = 7)
    private java.math.BigDecimal siteLng;

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    private LocalDate plannedEndDate;

    @Column(name = "total_planned_weeks")
    private Integer totalPlannedWeeks;

    @Column(name = "plan_unit")
    private String planUnit; // 'WEEK' or 'MONTH' — granularity of the schedule grid

    @Column(name = "tracking_mode")
    private String trackingMode = "SIMPLE"; // 'SIMPLE' (one progress %) or 'DETAILED' (per-period grid)

    @Column(name = "created_by")
    private Long createdBy;

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