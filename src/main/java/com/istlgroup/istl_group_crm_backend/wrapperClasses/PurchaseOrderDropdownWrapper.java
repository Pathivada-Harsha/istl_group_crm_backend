package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderDropdownWrapper {
    private Long id;
    private String poNo;
    private String vendorName;
    private String status;
    private String projectId;
    private String groupName;
    private String subGroupName;
}
