package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;

public class SaveMenuPermissionsRequest {
    private Integer role_id;
    private List<Integer> menu_ids; // IDs of menus that have permission = true

    public Integer getRole_id() { return role_id; }
    public void setRole_id(Integer role_id) { this.role_id = role_id; }

    public List<Integer> getMenu_ids() { return menu_ids; }
    public void setMenu_ids(List<Integer> menu_ids) { this.menu_ids = menu_ids; }
}