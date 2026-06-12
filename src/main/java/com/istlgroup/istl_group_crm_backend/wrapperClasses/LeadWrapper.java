package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

/**
 * Replace existing LeadWrapper.java with this.
 *
 * Changes: added address, solarScheme, bd fields, telecaller name
 * so the Leads page can show who is handling each lead.
 */
@Data
public class LeadWrapper {
    private Long   id;
    private String leadCode;
    private Long   customerId;
    private String name;
    private String email;
    private String phone;
    private String source;
    private String priority;
    private String status;
    private Long   assignedTo;
    private String assignedToName;      // telecaller name
    private String enquiry;
    private String groupName;
    private String subGroupName;
    private Long   createdBy;
    private String createdByName;
    private String createdAt;
    private String updatedAt;

    // Followup info
    private Boolean hasPendingFollowups;
    private Integer pendingFollowupsCount;

    // ── Telecaller info ───────────────────────────────────────────────────────
    private String telecallerStatus;       // NEW | INTERESTED | NOT_INTERESTED | NOT_RESPONDED | KEEP_IN_VIEW
    private String telecallerName;         // name of assigned telecaller
    private String telecallerReason;       // reason for NOT_INTERESTED / KIV conversation note
    private String kivReminderDate;        // ISO date string when KIV lead should resurface
    private String tcDiscussionNote;       // discussion note when marked INTERESTED
    private String tcLocation;             // address entered by telecaller
    private String tcSiteVisitDate;        // customer availability for site visit
    private String tcPropertyType;         // Residential | Commercial | Industrial
    private String tcQuotedPrice;          // price discussed with customer
    private String tcAddons;               // extra requirements noted
    private String tcOtherComments;        // other comments for BD team

    // ── New INTERESTED extra fields ───────────────────────────────────────────
    private String tcMonthlyBill;          // monthly electricity bill
    private String tcExistingContractLoad; // existing sanctioned load
    private String tcRequiredContractLoad; // required contract load after solar
    private String tcBillFileName;         // original uploaded bill filename
    private String tcBillFileType;         // MIME type of uploaded bill
    private Boolean tcHasBillFile;         // true if a bill file is stored in DB

    // ── BD info ───────────────────────────────────────────────────────────────
    private Long   bdAssignedToId;
    private String bdAssignedToName;       // name of assigned BD executive
    private String bdAssignedAt;

    // Closed Won attribution
    private Long   closedByUserId;
    private String closedByName;

    // ── Address ───────────────────────────────────────────────────────────────
    private String state;
    private String district;
    private String city;
    private String pincode;

    // ── Solar ─────────────────────────────────────────────────────────────────
    private String solarScheme;

    // ── Referral details ───────────────────────────────────────────────────────
    private String referralName;
    private String referralPhone; 

    // ── Project Capacity ──────────────────────────────────────────────────────
    private String capacity; 
    private String capacityUnit;

    // ── Lead Owner ────────────────────────────────────────────────────────────
    private String leadOwner;
    private Long   leadOwnerUserId;    // id of the user whose name matches leadOwner
    private String leadOwnerAvatarUrl; // "db" if they have a photo, else null
}