package com.istlgroup.istl_group_crm_backend.wrapperClasses;

public class MenuPermissionWrapper {

    private Integer menuId;
    private String menuName;
    private Boolean hasPermission;

    // Required for JPA projection mapping
    public MenuPermissionWrapper(Integer menuId, String menuName, Boolean hasPermission) {
        this.menuId = menuId;
        this.menuName = menuName;
        this.hasPermission = hasPermission;
    }

    public Integer getMenuId() { return menuId; }
    public String getMenuName() { return menuName; }
    public Boolean getHasPermission() { return hasPermission; }
    public void setMenuId(Integer menuId) { this.menuId = menuId; }
    public void setMenuName(String menuName) { this.menuName = menuName; }
    public void setHasPermission(Boolean hasPermission) { this.hasPermission = hasPermission; }
}