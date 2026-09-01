// ─────────────────────────────────────────────────────────────────────────────
// PROVISIONAL FEATURE — "Orders in Line"
// Temporary stopgap register, scheduled for replacement by a permanent pipeline
// module. Data here migrates into the leads table at that point.
// Removal: drop table `orders_in_line`, delete the OrdersInLine* files, revert the
// two lines in Dashboard.js, the sidebar entry, and the App.js import + route.
// ─────────────────────────────────────────────────────────────────────────────
package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Request AND response DTO for an Orders-in-Line record (Tender convention:
 * one wrapper for both directions). Every scalar is a String so the loose
 * frontend typing survives the round trip; OrdersInLineService parses to the
 * typed columns and formats back out.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrdersInLineWrapper {

    private Long id;

    private String clientName;
    private String sourceParty;
    private String sourceType;

    private String capacity;
    private String capacityUnit;
    private String capacityType;   // "AC" | "DC"

    private String category;
    private String state;
    private String district;

    private String contactPerson;
    private String phone;
    private String email;

    private String estimatedValue;
    private String receivedDate;           // yyyy-MM-dd
    private String expectedDecisionDate;   // yyyy-MM-dd

    private String status;
    private Long   ownerUserId;
    private String remarks;

    private Long   createdBy;
    private String createdAt;
    private String updatedAt;
}
