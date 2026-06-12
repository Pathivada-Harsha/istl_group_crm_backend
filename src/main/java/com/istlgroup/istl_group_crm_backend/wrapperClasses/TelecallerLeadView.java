package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

/**
 * View DTO returned to the telecaller frontend for each lead.
 */
@Data
public class TelecallerLeadView {
    private Long    id;
    private String  leadCode;
    private String  name;
    private String  email;
    private String  phone;
    private String  source;
    private String  priority;
    private String  enquiry;
    private String  groupName;
    private String  subGroupName;
 
    // ── Address (from lead record) ───────────────────────────────────────────
    private String  state;
    private String  district;
    private String  city;
    private String  pincode;

    // Solar scheme
    private String  solarScheme;

    /** Whether the customer wants PM Surya Ghar subsidy. Values: Yes | No | null */
    private String  subsidyRequired;

    // ── Telecaller INTERESTED fields ─────────────────────────────────────────

    /** Summary of the telecaller's discussion with the customer. */
    private String  tcDiscussionNote;

    /**
     * Location / address entered by the telecaller when marking INTERESTED.
     * May differ from or supplement the lead's state/city fields.
     */
    private String  tcLocation;

    /** Customer's available date for site visit (formatted as dd-MM-yyyy). */
    private String  tcSiteVisitDate;

    /**
     * Property type selected by telecaller.
     * Values: Residential | Commercial | Industrial
     */
    private String  tcPropertyType;

    /** Quoted price / amount discussed with the customer. */
    private String  tcQuotedPrice;

    /** Add-ons or extra requirements noted by telecaller. */
    private String  tcAddons;

    /** Any other comments from telecaller to the BD team. */
    private String  tcOtherComments;

    // ── Status ───────────────────────────────────────────────────────────────

    /** NEW | INTERESTED | NOT_INTERESTED | NOT_RESPONDED | KEEP_IN_VIEW */
    private String  telecallerStatus;
    private String  telecallerReason;

    /** Date when KEEP_IN_VIEW lead should resurface (ISO date string) */
    private String  kivReminderDate;

    /** Main lead status: New | Contacted | In Discussion | Closed Won | Closed Lost */
    private String  leadStatus;

    /** true when status = INTERESTED (handed off to BD) */
    private boolean handedOffToBD;

    // ── Team ─────────────────────────────────────────────────────────────────
    private String  telecallerName;
    private Long    bdAssignedToId;
    private String  bdAssignedToName;
    private String  bdAssignedAt;

    private String  createdAt;
    private String  telecallerStatusUpdatedAt;

    // ── Referral details ──────────────────────────────────────────────────────
    private String  referralName;
    private String  referralPhone;

    // ── Project Capacity ──────────────────────────────────────────────────────
    private String  capacity;
    private String  capacityUnit;

    // ── New INTERESTED extra fields ───────────────────────────────────────────
    /** Monthly electricity bill amount entered by telecaller. */
    private String  tcMonthlyBill;

    /** Existing sanctioned/contract load from electricity board. */
    private String  tcExistingContractLoad;

    /** Required contract load after solar installation. */
    private String  tcRequiredContractLoad;

    /** Original filename of the uploaded electricity bill (if any). */
    private String  tcBillFileName;

    /** MIME type of the uploaded bill (e.g. "application/pdf"). */
    private String  tcBillFileType;

    /** true if a bill file BLOB is stored in DB for this lead. */
    private Boolean tcHasBillFile;
}