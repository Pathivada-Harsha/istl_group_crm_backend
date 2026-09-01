package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Request + response DTO for a Parent Group or Sub Group. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompanyGroupWrapper {

    private Long id;
    private String groupName;
    private Long parentGroupId;
    /** Convenience mirror so the picker needn't look it up separately. */
    private String parentGroupName;
    /** "GROUP" (no parent) or "SUB_GROUP" (has a parent) — read-only. */
    private String type;

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
}
