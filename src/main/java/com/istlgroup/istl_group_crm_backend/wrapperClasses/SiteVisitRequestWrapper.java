package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class SiteVisitRequestWrapper {

    // Link / metadata
    private Long leadId;
    private Long followupId;   // present when filled from an assigned "Visit" follow-up
    private String visitDate;  // "yyyy-MM-dd"

    // Location
    private String siteAddress;
    private Double siteLat;
    private Double siteLng;

    // Overlapping fields — written back to the lead (single source of truth)
    private String monthlyBill;            // -> lead.tcMonthlyBill
    private String existingContractLoad;   // -> lead.tcExistingContractLoad
    private String requiredContractLoad;   // -> lead.tcRequiredContractLoad
    private String capacity;               // -> lead.capacity
    private String capacityUnit;           // -> lead.capacityUnit
    private String propertyType;           // -> lead.tcPropertyType

    // Project details (site-visit specific)
    private String sanctionedLoad;
    private String electricityTariff;
    private String consumerNumber;
    private String discom;
    private String phase;
    private String monthlyConsumptionUnits;

    // Roof details
    private String roofType;
    private String shadowFreeArea;
    private String roofCondition;
    private String roofDirection;
    private String roofTilt;
    private String accessibility;

    // Shadow analysis
    private String shadowAnalysis; // NONE | PARTIAL | HEAVY
    private String shadowRemarks;

    // Installation details
    private String inverterLocation;
    private String acCableLength;
    private String dcCableLength;
    private String earthingLocation;
    private String structureType;
    private String moduleOrientation;
}
