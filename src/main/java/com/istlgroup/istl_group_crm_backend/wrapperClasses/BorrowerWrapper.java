package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Request + response DTO for a borrower.
 *
 * <p>On list calls the nested {@code sanctions} carries only the latest letter
 * so the registry table can show a ref no. and amount per row; on a detail call
 * it carries all of them.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BorrowerWrapper {

    private Long id;

    // ── identity ──
    private String borrowerName;
    private String cin;
    private String pan;
    private String sponsorName;

    // ── parties (registry sheet: Borrower Details) ──
    private String promoterName;
    private String guarantorName;
    private String groupName;
    /** Registry sheet "Cat" / "Sub Cat" — not the sanction's project category. */
    private String borrowerCategory;
    private String borrowerSubCategory;

    // ── registered office ──
    private String registeredAddress;
    private String city;
    private String state;
    private String pincode;

    // ── contact ──
    private String contactPerson;
    private String contactEmail;
    private String contactPhone;

    private String notes;
    private Long projectId;

    private List<BorrowerSanctionWrapper> sanctions = new ArrayList<>();

    // ── computed for the list view, read-only ──
    /** How many of the seven identity fields are filled. */
    private Integer identityFilled;
    private Integer identityTotal;
    /** Convenience mirrors of the latest sanction, so the table needn't dig. */
    private String latestRefNo;
    private String latestSanctionedAmount;
    private String latestCategory;
    private String latestScheduledCod;
    private String latestCodStatus;

    private String createdAt;
    private String updatedAt;
}
