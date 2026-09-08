package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Request + response DTO for a Parent Group or Sub Group. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompanyGroupWrapper {

    private Long id;

    /**
     * On a WRITE (createGroup), a caller may attach exactly one new sanction
     * here for THIS group directly — the first entry is persisted in the
     * same transaction as the group, right after it's created, mirroring
     * {@link BorrowerWrapper#getSanctions()}. Only meaningful when the group
     * itself is the sanctioned entity (no company beneath it); a group
     * created purely as an organisational container leaves this empty.
     */
    private List<BorrowerSanctionWrapper> sanctions = new ArrayList<>();
    /** Paired with {@code sanctions} above. */
    private String rawExtractedJson;
    private String groupName;
    private Long parentGroupId;
    /** Convenience mirror so the picker needn't look it up separately. */
    private String parentGroupName;
    /** "GROUP" (no parent) or "SUB_GROUP" (has a parent) — read-only. */
    private String type;

    /** Optional master CIN for this Group itself — independent of any company's own CIN. */
    private String cin;
    /** Optional master registered address for this Group itself — independent of any company's own. */
    private String registeredAddress;

    private String createdAt;
    private String updatedAt;

    // ── Group Detail summary fields — only populated by GET /borrower/groups/{id},
    //    read-only, never sent back on a create/update request. Direct
    //    Companies and Sub Groups themselves are fetched separately, one
    //    page at a time, so this node carries only the counts/totals the
    //    stat cards and Sub Group delete-confirm copy need. ──

    /** Whether this group has any Sub Groups of its own — a Sub Group never does. */
    private Boolean hasSubGroups;
    /** Companies sitting directly under this exact group. */
    private Integer directCompaniesCount;
    /** Sub Groups sitting directly under this group (0 for a Sub Group). */
    private Integer subGroupsCount;
    /** Every company under this group's own hierarchy — direct plus every Sub Group's. */
    private Integer totalCompaniesCount;
    /** Same scope as {@code totalCompaniesCount}, counting only SPVs. */
    private Integer totalSpvCount;
    /** Sanction letters across every company in this group's own hierarchy. */
    private Integer sanctionsCount;
    /** Sanctioned amount across every company in this group's own hierarchy. */
    private String totalSanctionedAmount;
    /** Companies sitting directly under a Sub Group — only set on a Sub Group's own summary. */
    private Integer companiesCount;
    /**
     * Read-only, derived: "Active" if at least one company anywhere under
     * this group's own hierarchy (direct, or under one of its Sub Groups) is
     * Active, else "Inactive" — never stored independently and never sent
     * back on a create/update request. Never derived from sanctions.
     */
    private String status;
}
