package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

/**
 * Request body for PUT /telecaller/lead/{id}/status
 */
@Data
public class TelecallerStatusUpdateRequest {

    /** INTERESTED | NOT_INTERESTED | NOT_RESPONDED */
    private String telecallerStatus;

    /** Required when status = NOT_INTERESTED */
    private String reason;

    /**
     * Required when status = INTERESTED.
     */
    private String discussionNote;

    // ── Extra fields collected only when status = INTERESTED ─────────────────

    private String tcLocation;
    private String tcSiteVisitDate;
    private String tcPropertyType;
    private String tcQuotedPrice;
    private String tcAddons;
    private String tcOtherComments;

    // ── Individual address fields (parsed from location input in frontend) ────
    private String tcState;
    private String tcDistrict;
    private String tcCity;
    private String tcPincode;

    // ── New fields for INTERESTED status ─────────────────────────────────────

    /** Monthly electricity bill amount */
    private String tcMonthlyBill;

    /** Existing contract load (sanctioned load from electricity board) */
    private String tcExistingContractLoad;

    /** Required contract load */
    private String tcRequiredContractLoad;

    // ── Project capacity ─────────────────────────────────────────────────────
    private String capacity;
    private String capacityUnit;

    /**
     * Only relevant when telecallerStatus = NOT_INTERESTED.
     */
    private Boolean needsFollowUp;

    // ── Scheme fields (relevant when subGroupName is Solar_Rooftop / Solar_ground_mounted) ──
    private String solarScheme;
    private String subsidyRequired;

    // ── Keep in View ──────────────────────────────────────────────────────────
    /** ISO date string (yyyy-MM-dd) for when the KIV lead should be contacted back. */
    private String kivReminderDate;
}