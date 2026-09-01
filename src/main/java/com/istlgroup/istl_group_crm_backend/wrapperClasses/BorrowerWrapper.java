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
    /** Legacy free-text group name, still populated by the extractor as a signal. */
    private String groupName;
    /** Registry sheet "Cat" / "Sub Cat" — not the sanction's project category. */
    private String borrowerCategory;
    private String borrowerSubCategory;

    /**
     * Whether the caller may rename/move/delete this borrower record itself
     * (strict owner-or-teammate Role Hierarchy scope), as opposed to merely
     * being able to see it — which can also happen via a sanction the
     * caller personally attached to a borrower someone else owns. Only set
     * on the response from GET /borrower/{id}; null on write-completion
     * responses, which the caller always follows with a fresh GET anyway.
     */
    private Boolean canEditBorrower;

    // ── company hierarchy ──
    /** FK to company_groups — the Parent Group or Sub Group this company sits directly under. NULL = standalone. */
    private Long groupId;
    private Boolean isSubsidiary = false;
    private Boolean isSpv = false;
    /** Derived read-only label: Standalone / Subsidiary / SPV / Subsidiary + SPV. */
    private String companyType;
    /** Top-level Parent Group, resolved even when groupId points at a Sub Group. */
    private Long parentGroupId;
    private String parentGroupName;
    /** Set only when the company sits under a Sub Group (i.e. groupId's own row has a parent). */
    private Long subGroupId;
    private String subGroupName;
    private java.util.List<String> aliases = new ArrayList<>();
    /** Rollups for the hierarchy tree — all live sanctions, not just the latest. */
    private Integer sanctionsCount;
    private String totalSanctionedAmount;

    // ── registered office ──
    private String registeredAddress;
    private String city;
    private String state;
    private String pincode;
    private String district;

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
