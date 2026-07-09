package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class SiteVisitWrapper {

    private Long id;
    private Long leadId;
    private Long followupId;
    private Long visitedByUserId;
    private String visitedByName;
    private String visitDate;

    // Location
    private String siteAddress;
    private Double siteLat;
    private Double siteLng;

    // Overlapping fields — echoed from the lead so the report renders complete
    private String monthlyBill;
    private String existingContractLoad;
    private String requiredContractLoad;
    private String capacity;
    private String capacityUnit;
    private String propertyType;

    // Project details
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
    private String shadowAnalysis;
    private String shadowRemarks;

    // Installation details
    private String inverterLocation;
    private String acCableLength;
    private String dcCableLength;
    private String earthingLocation;
    private String structureType;
    private String moduleOrientation;

    // Audit
    private Long createdBy;
    private String createdByName;
    private String createdAt;
    private String updatedAt;
}
