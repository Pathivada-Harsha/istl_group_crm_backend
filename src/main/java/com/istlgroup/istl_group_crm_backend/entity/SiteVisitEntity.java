package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A structured "Solar Site Visit Report" recorded against a lead.
 *
 * One-to-many per lead — a lead may have several visits over time. Only the
 * site-visit-specific fields live here; overlapping fields (monthly bill,
 * contract loads, capacity, property type, location) remain on the lead as the
 * single source of truth and are written back there on save.
 */
@Entity
@Table(name = "site_visit")
@Data
public class SiteVisitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Link / metadata ─────────────────────────────────────────────────────
    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** Set when the report is filled from an assigned "Visit" follow-up. */
    @Column(name = "followup_id")
    private Long followupId;

    @Column(name = "visited_by_user_id")
    private Long visitedByUserId;

    @Column(name = "visited_by_name", length = 150)
    private String visitedByName;

    @Column(name = "visit_date")
    private LocalDate visitDate;

    // ── Location (mirrors order-book naming) ─────────────────────────────────
    @Column(name = "site_address", length = 500)
    private String siteAddress;

    @Column(name = "site_lat")
    private Double siteLat;

    @Column(name = "site_lng")
    private Double siteLng;

    // ── Project details (new) ────────────────────────────────────────────────
    @Column(name = "sanctioned_load", length = 100)
    private String sanctionedLoad;

    @Column(name = "electricity_tariff", length = 100)
    private String electricityTariff;

    @Column(name = "consumer_number", length = 100)
    private String consumerNumber;

    @Column(name = "discom", length = 150)
    private String discom;

    @Column(name = "phase", length = 50)
    private String phase;

    @Column(name = "monthly_consumption_units", length = 100)
    private String monthlyConsumptionUnits;

    // ── Roof details (new) ───────────────────────────────────────────────────
    @Column(name = "roof_type", length = 100)
    private String roofType;

    @Column(name = "shadow_free_area", length = 100)
    private String shadowFreeArea;

    @Column(name = "roof_condition", length = 100)
    private String roofCondition;

    @Column(name = "roof_direction", length = 100)
    private String roofDirection;

    @Column(name = "roof_tilt", length = 100)
    private String roofTilt;

    @Column(name = "accessibility", length = 100)
    private String accessibility;

    // ── Shadow analysis (new) ────────────────────────────────────────────────
    @Column(name = "shadow_analysis", length = 20)
    private String shadowAnalysis; // NONE | PARTIAL | HEAVY

    @Column(name = "shadow_remarks", columnDefinition = "TEXT")
    private String shadowRemarks;

    // ── Installation details (new) ───────────────────────────────────────────
    @Column(name = "inverter_location", length = 150)
    private String inverterLocation;

    @Column(name = "ac_cable_length", length = 100)
    private String acCableLength;

    @Column(name = "dc_cable_length", length = 100)
    private String dcCableLength;

    @Column(name = "earthing_location", length = 150)
    private String earthingLocation;

    @Column(name = "structure_type", length = 150)
    private String structureType;

    @Column(name = "module_orientation", length = 100)
    private String moduleOrientation;

    // ── Audit ────────────────────────────────────────────────────────────────
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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
