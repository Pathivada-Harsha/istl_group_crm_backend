package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for vendor dropdown
 * Currently using Map<String, Object> in repository queries
 * This DTO is kept for future type-safe implementations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorDropdownDTO {
    private Long id;
    private String name;
    private String phone;
    private Integer quotationCount; // Optional: for history endpoint (not used currently)

    // Constructor for basic dropdown (without quotation count)
    public VendorDropdownDTO(Long id, String name, String phone) {
        this.id = id;
        this.name = name;
        this.phone = phone;
    }
}