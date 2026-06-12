package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;

import lombok.Data;

@Data
public class AssignPermissionsRequest {

	private Integer role_id;
    private List<Integer> permission_ids;
}
