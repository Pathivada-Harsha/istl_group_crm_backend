package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name="leads")
@Data
public class LeadsEntity {
	  @Id
	    @GeneratedValue(strategy = GenerationType.IDENTITY)
	    private Long id;

	    @Column(name = "lead_code", unique = true)
	    private String leadCode;

	    @Column(name = "customer_id")
	    private Long customerId;

	    @Column(name = "name", nullable = false)
	    private String name;

	    @Column(name = "email")
	    private String email;

	    @Column(name = "phone")
	    private String phone;

	    @Column(name = "source")
	    private String source;

	    @Column(name = "priority")
	    private String priority;

	    @Column(name = "status")
	    private String status;

	    @Column(name = "assigned_to")
	    private Long assignedTo;

	    @Column(name = "enquiry", columnDefinition = "TEXT")
	    private String enquiry;

	    @Column(name = "group_name")
	    private String groupName;

	    @Column(name = "sub_group_name")
	    private String subGroupName;

	    @Column(name = "created_by")
	    private Long createdBy;

	    @Column(name = "created_at", updatable = false)
	    private LocalDateTime createdAt;

	    @Column(name = "updated_at")
	    private LocalDateTime updatedAt;

	    @Column(name = "deleted_at")
	    private LocalDateTime deletedAt;
	    
	    /**
	     * Telecaller's assessment of this lead.
	     * Values: PENDING (null/default) | INTERESTED | NOT_INTERESTED | NOT_RESPONDED
	     */
	    @Column(name = "telecaller_status", length = 30)
	    private String telecallerStatus;

	    /**
	     * Reason provided when telecallerStatus = NOT_INTERESTED.
	     */
	    @Column(name = "telecaller_reason", columnDefinition = "TEXT")
	    private String telecallerReason;

	    /**
	     * Date when a KEEP_IN_VIEW lead should resurface for callback.
	     * Set by the telecaller when marking a lead as Keep in View.
	     */
	    @Column(name = "kiv_reminder_date")
	    private java.time.LocalDate kivReminderDate;

	    /**
	     * Timestamp of the last telecaller status change.
	     * Used by the 7-day re-queue scheduler.
	     */
	    @Column(name = "telecaller_status_updated_at")
	    private LocalDateTime telecallerStatusUpdatedAt;
	    @Column(name = "state", length = 100)
	    private String state;

	    @Column(name = "district", length = 100)
	    private String district;

	    @Column(name = "city", length = 100)
	    private String city;

	    @Column(name = "pincode", length = 10)
	    private String pincode;

	    // ── Solar scheme (only when subGroupName = 'Solar_Rooftop') ─────────────
	    // Values: PM_Surya_Ghar | PM_Kusum | State_Subsidy | Net_Metering_Only | No_Scheme | Others

	    @Column(name = "solar_scheme", length = 50)
	    private String solarScheme;

	    /**
	     * Whether the customer wants the PM Surya Ghar subsidy.
	     * Values: "Yes" | "No" | null (not asked / not applicable)
	     */
	    @Column(name = "subsidy_required", length = 10)
	    private String subsidyRequired;

	    // ── Telecaller discussion note (filled when marking INTERESTED) ──────────

	    @Column(name = "tc_discussion_note", columnDefinition = "TEXT")
	    private String tcDiscussionNote;

    // ── Telecaller INTERESTED extra fields ──────────────────────────────────

    /**
     * Address / location entered by telecaller when marking INTERESTED.
     * Populated if the lead had no address, or to capture a more specific location.
     */
    @Column(name = "tc_location", length = 255)
    private String tcLocation;

    /** Date when customer is available for a site visit. */
    @Column(name = "tc_site_visit_date")
    private java.time.LocalDate tcSiteVisitDate;

    /**
     * Property type the customer is interested in.
     * Values: Residential | Commercial | Industrial
     */
    @Column(name = "tc_property_type", length = 30)
    private String tcPropertyType;

    /** Price / amount quoted to the customer (free text, e.g. "1.2L", "1,20,000"). */
    @Column(name = "tc_quoted_price", length = 100)
    private String tcQuotedPrice;

    /** Add-ons or additional requirements noted by telecaller. */
    @Column(name = "tc_addons", columnDefinition = "TEXT")
    private String tcAddons;

    /** Any other comments from telecaller for the BD team. */
    @Column(name = "tc_other_comments", columnDefinition = "TEXT")
    private String tcOtherComments;

    // ── New INTERESTED fields ────────────────────────────────────────────────

    /** Monthly electricity bill amount (e.g. "2500", "₹2,500") */
    @Column(name = "tc_monthly_bill", length = 100)
    private String tcMonthlyBill;

    /** Existing sanctioned/contract load from electricity board */
    @Column(name = "tc_existing_contract_load", length = 100)
    private String tcExistingContractLoad;

    /** Required contract load after solar installation */
    @Column(name = "tc_required_contract_load", length = 100)
    private String tcRequiredContractLoad;

    /** Original filename of the uploaded bill (e.g. "electricity_bill_may.pdf") */
    @Column(name = "tc_bill_file_name", length = 255)
    private String tcBillFileName;

    /** MIME type of the uploaded bill file (e.g. "application/pdf", "image/jpeg") */
    @Column(name = "tc_bill_file_type", length = 100)
    private String tcBillFileType;

    /**
     * Binary content of the uploaded bill file — stored as BLOB in DB (max 10 MB).
     * Loaded LAZILY so list queries don't fetch the full BLOB for every row.
     */
    @jakarta.persistence.Lob
    @jakarta.persistence.Basic(fetch = jakarta.persistence.FetchType.LAZY)
    @Column(name = "tc_bill_file_data", columnDefinition = "LONGBLOB")
    private byte[] tcBillFileData;

    /**
     * Simple boolean flag — set to true when a bill file is stored.
     * Used in list/wrapper responses instead of checking tcBillFileData != null
     * (which would force BLOB loading for every row).
     */
    @Column(name = "tc_has_bill_file", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    private boolean tcHasBillFile = false;

    // ── BD assignment (populated by round-robin when TC marks INTERESTED) ───

    @Column(name = "bd_assigned_to")
    private Long bdAssignedTo;

    @Column(name = "bd_assigned_at")
    private LocalDateTime bdAssignedAt;

    // ── Closed Won attribution ────────────────────────────────────────────────

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "closed_by_name", length = 200)
    private String closedByName;

    // ── Referral details (when source = "Referral") ──────────────────────────

    @Column(name = "referral_name", length = 200)
    private String referralName;

    @Column(name = "referral_phone", length = 20)
    private String referralPhone;

    // ── Project Capacity ─────────────────────────────────────────────────────

    @Column(name = "capacity", length = 50)
    private String capacity;

    @Column(name = "capacity_unit", length = 20)
    private String capacityUnit;

	    // ── Lead Owner (free text — user from dropdown or manually typed) ─────────

    @Column(name = "lead_owner", length = 200)
    private String leadOwner;

    /* ================= Lifecycle ================= */

	    @PrePersist
	    protected void onCreate() {
	        this.createdAt = LocalDateTime.now();
	        this.updatedAt = LocalDateTime.now();
	    }

	    @PreUpdate
	    protected void onUpdate() {
	        this.updatedAt = LocalDateTime.now();
	    }

    // Proposed selling price used by the Budget Estimation feature.
    @Column(name = "proposed_selling_price")
    private BigDecimal proposedSellingPrice;
}