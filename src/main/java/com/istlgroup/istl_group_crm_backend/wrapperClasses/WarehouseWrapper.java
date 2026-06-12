package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.WarehouseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbound + inbound DTO for warehouses.
 * Shape: { id, code, name, city, inCharge, isActive, groupName, subGroupName, ... }
 *
 * Note: there is no projectId field — warehouses are not scoped by project.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseWrapper {
    private Long    id;
    private String  code;
    private String  name;
    private String  city;
    private String  address;
    private String  inCharge;
    private String  phone;
    private String  groupName;
    private String  subGroupName;
    private Boolean isActive;
    private String  notes;

    public static WarehouseWrapper from(WarehouseEntity e) {
        if (e == null) return null;
        return WarehouseWrapper.builder()
            .id(e.getId())
            .code(e.getCode())
            .name(e.getName())
            .city(e.getCity())
            .address(e.getAddress())
            .inCharge(e.getInCharge())
            .phone(e.getPhone())
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .isActive(e.getIsActive() != null ? e.getIsActive() : Boolean.TRUE)
            .notes(e.getNotes())
            .build();
    }
}