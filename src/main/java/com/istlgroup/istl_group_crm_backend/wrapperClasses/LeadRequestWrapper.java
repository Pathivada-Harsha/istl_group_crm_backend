package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;


@Data
public class LeadRequestWrapper {
    private Long   customerId;
    private String name;          // required
    private String email;         // OPTIONAL — not required
    private String phone;
    private String source;
    private String priority;
    private String status;
    private Long   assignedTo;
    private String enquiry;
    private String groupName;
    private String subGroupName;
    private String closedLostReason;

    // ── Closed Won attribution ────────────────────────────────────────────────
    private Long   closedByUserId;
    private String closedByName;

    // ── New address fields ────────────────────────────────────────────────────
    private String state;
    private String district;
    private String city;
    private String pincode;

    // ── Solar scheme (only when subGroupName = Solar_Rooftop) ────────────────
    private String solarScheme;

    // ── PM Surya Ghar subsidy selection ──────────────────────────────────────
    // Values: "Yes" | "No" | null
    private String subsidyRequired;

    // ── Additional notes (appended to enquiry or stored separately) ───────────
    private String notes;

    // ── Import helper — resolve assignedTo by email ───────────────────────────
    private String assignedToEmail;

    // ── Referral details (only when source = "Referral") ─────────────────────
    private String referralName;
    private String referralPhone;

    // ── Project Capacity ──────────────────────────────────────────────────────
    private String capacity;
    private String capacityUnit;

    // ── Lead Owner (name — from user dropdown or free-text 'Other') ──────────
    private String leadOwner;
}