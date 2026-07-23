package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Request + response DTO for a tender, mirroring the frontend tenderData.js
 * shape. All scalar fields (numbers, dates) are String to tolerate the
 * frontend's loose typing and empty values; the service parses them into the
 * entity's typed columns. Child collections are nested inline.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenderWrapper {

    private Long id;

    // identification
    private String tenderNumber;
    private String tenderName;
    private String issuingAuthority;

    // client / developer KYC
    private String clientCompany;
    private String clientType;
    private String clientGstin;
    private String clientPan;
    private String clientCin;
    private String clientContactPerson;
    private String clientContactEmail;
    private String clientContactPhone;
    private String clientAddress;
    private String clientCity;
    private String clientState;

    // classification / location
    private String sector;
    private String tenderType;
    private String source;
    private String portalLink;
    private String location;
    private String district;
    private String state;
    private String financialYear;

    // financials
    private String estimatedValue;
    private String emdAmount;
    private String performanceSecurityPct;

    // key dates
    private String submissionDeadline;
    private String technicalOpeningDate;
    private String financialOpeningDate;

    // eligibility roll-up
    private String eligibilityDecision;

    // rate analysis & bid knobs
    private String overheadPct;
    private String profitPct;

    // workflow: go/no-go
    private String goNoGo;
    private String goNoGoReason;
    private String goNoGoDate;

    // workflow: approvals
    private String cfoApprovalStatus;
    private String cfoApprovalRemarks;
    private String cfoApprovalDate;
    private String mdApprovalStatus;
    private String mdApprovalRemarks;
    private String mdApprovalDate;

    // submission (single reference)
    private String submissionMode;
    private String submissionReference;
    private String submissionDate;
    private String submittedBy;

    // result
    private String l1Value;
    private String ourRank;
    private String status;
    private String lossReason;
    private String result;
    private String competitorNotes;
    private String contractValue;
    private String loaNumber;
    private String loaDate;
    private String agreementDate;
    private String projectId;

    // response-only
    private String createdAt;

    // source-PDF metadata (response-only; bytes never travel in JSON — they ride
    // the multipart upload/download endpoints)
    private String sourcePdfName;
    private String sourcePdfMimeType;
    private Boolean hasSourcePdf;

    // child collections
    private List<TenderBoqItemWrapper> boqItems;
    private List<TenderEligibilityWrapper> eligibilityCriteria;
    private List<TenderDocumentWrapper> documents;
    private List<TenderDocRequestWrapper> docRequests;
    private List<TenderApprovalLogWrapper> approvalLog;
}
