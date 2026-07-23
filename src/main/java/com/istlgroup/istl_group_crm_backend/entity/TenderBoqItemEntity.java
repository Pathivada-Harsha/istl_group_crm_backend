package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A BOQ line item for the Rate Analysis & Bid tab. our_rate / amount / variance
 * are derived on the client and NOT stored — only the raw inputs live here.
 */
@Entity
@Table(name = "tender_boq_items")
@Data
public class TenderBoqItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "sort_no")
    private Integer sortNo = 0;

    @Column(name = "item_no")
    private String itemNo;

    @Column(name = "scope")
    private String scope;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "unit")
    private String unit = "Nos";

    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity;

    // rate quoted in the tender document (NIT / estimated rate)
    @Column(name = "tender_rate", precision = 18, scale = 4)
    private BigDecimal tenderRate;

    @Column(name = "material_rate", precision = 18, scale = 4)
    private BigDecimal materialRate;

    @Column(name = "labour_rate", precision = 18, scale = 4)
    private BigDecimal labourRate;
}
