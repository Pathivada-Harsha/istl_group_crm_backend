// ─────────────────────────────────────────────────────────────────────────────
// PROVISIONAL FEATURE — "Orders in Line"
// Temporary stopgap register, scheduled for replacement by a permanent pipeline
// module. Data here migrates into the leads table at that point.
// Removal: drop table `orders_in_line`, delete the OrdersInLine* files, revert the
// two lines in Dashboard.js, the sidebar entry, and the App.js import + route.
// ─────────────────────────────────────────────────────────────────────────────
package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A potential order that arrived informally — referred by a developer, channel
 * partner, or direct enquiry — where it is not yet known whether it will be
 * confirmed. Deliberately NOT a lead: nothing here feeds any lead listing,
 * count, conversion metric, funnel, or report.
 *
 * <p>Column set mirrors the leads shape so the eventual migration into
 * {@code leads} is a field-to-field copy. In particular {@code capacity} and
 * {@code capacity_unit} stay as two separate columns because
 * {@code util.CapacityUtil} expects the pair, and {@code category} holds a raw
 * {@code sub_groups.sub_group_name} value so it matches {@code leads.sub_group_name}
 * exactly.
 *
 * <p>{@code owner_user_id} / {@code created_by} are plain Long values with no
 * {@code @ManyToOne} and no FK constraint (loose-FK house style, mirroring
 * OrderBook and Tender) — this table must be droppable on its own.
 */
@Entity
@Table(name = "orders_in_line")
@Data
public class OrdersInLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── who / what ──
    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "source_party")
    private String sourceParty;

    @Column(name = "source_type", length = 50)
    private String sourceType;

    // Two columns on purpose — see the class javadoc.
    @Column(name = "capacity", length = 50)
    private String capacity;

    @Column(name = "capacity_unit", length = 20)
    private String capacityUnit;

    /** "AC" or "DC" — solar capacity is quoted either way and they are not interchangeable. */
    @Column(name = "capacity_type", length = 10)
    private String capacityType;

    @Column(name = "category", length = 150)
    private String category;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "district", length = 100)
    private String district;

    // ── contact ──
    @Column(name = "contact_person")
    private String contactPerson;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email")
    private String email;

    // ── commercial (indicative only) ──
    @Column(name = "estimated_value", precision = 18, scale = 2)
    private BigDecimal estimatedValue;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    @Column(name = "expected_decision_date")
    private LocalDate expectedDecisionDate;

    @Column(name = "status", length = 40, nullable = false)
    private String status = "Enquiry Received";

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    // ── audit ──
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Soft delete marker — every read path excludes rows with a value here. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
