package com.istlgroup.istl_group_crm_backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One field in an item's "variant attributes" schema (stored as JSON on
 * bom_items_master.variant_attributes). A Solar PV Module item, for example,
 * defines fields like wattage / make / cell type; each item's variants then
 * fill these fields (see Slice 2).
 *
 * Serialized with NON_NULL so unused facets (options for non-dropdown, unit for
 * non-number) are omitted, keeping the stored JSON canonical and round-trippable.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VariantAttributeDef {
    /** Stable machine key, auto-slugged from the label on the client (e.g. "cell_type"). */
    private String key;
    /** Human label shown on the variant form (e.g. "Cell Type"). */
    private String label;
    /** "text" | "dropdown" | "number". */
    private String type;
    /** Allowed values — required and non-empty when type == "dropdown"; omitted otherwise. */
    private List<String> options;
    /** Display unit for numeric fields (e.g. "Wp"); the variant stores the bare number. Omitted for non-number. */
    private String unit;
    /** Whether a variant must supply a value for this field. */
    private Boolean required;
}
