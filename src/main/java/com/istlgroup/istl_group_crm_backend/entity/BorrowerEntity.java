package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Borrower master. Holds the entity's own identity — the things that are true
 * about the company regardless of any loan — so a borrower can exist with no
 * sanction at all.
 *
 * <p>Sanction letters live in {@code borrower_sanctions} and reference this row
 * by a plain {@code borrower_id} column (loose-FK pattern, as in tenders and
 * order book), so loading a borrower never drags its documents into memory.
 */
@Entity
@Table(name = "borrowers")
@Data
public class BorrowerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── identity ──
    @Column(name = "borrower_name", nullable = false)
    private String borrowerName;

    // The registry sheet's "SL Ref. No" is the sanction's own reference, not a
    // second number — it lives on BorrowerSanctionEntity.refNo.

    /** Unique when present; the natural key for a company, unlike its name. */
    @Column(name = "cin", length = 30)
    private String cin;

    @Column(name = "pan", length = 15)
    private String pan;

    /** Parent / promoter group behind the SPV. */
    @Column(name = "sponsor_name")
    private String sponsorName;

    // ── parties (registry sheet: Borrower Details) ──
    @Column(name = "promoter_name")
    private String promoterName;

    @Column(name = "guarantor_name")
    private String guarantorName;

    /** Legacy free-text group name; still populated by the extractor as a signal. */
    @Column(name = "group_name")
    private String groupName;

    /** FK to company_groups — the Parent Group or Sub Group this company sits directly under. NULL = standalone. */
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "is_subsidiary", nullable = false)
    private Boolean isSubsidiary = false;

    @Column(name = "is_spv", nullable = false)
    private Boolean isSpv = false;

    /**
     * The registry sheet's "Cat" / "Sub Cat". Deliberately not named
     * {@code category} — {@code BorrowerSanctionEntity.category} is the
     * project sector on a letter, and two fields called "category" on the
     * same screen would be read wrong.
     */
    @Column(name = "borrower_category", length = 120)
    private String borrowerCategory;

    @Column(name = "borrower_sub_category", length = 120)
    private String borrowerSubCategory;

    // ── registered office ──
    @Column(name = "registered_address", columnDefinition = "TEXT")
    private String registeredAddress;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "district", length = 120)
    private String district;

    // ── contact ──
    @Column(name = "contact_person", length = 200)
    private String contactPerson;

    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Reserved for the future link to a project; not populated yet. */
    @Column(name = "project_id")
    private Long projectId;

    // ── audit ──
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
