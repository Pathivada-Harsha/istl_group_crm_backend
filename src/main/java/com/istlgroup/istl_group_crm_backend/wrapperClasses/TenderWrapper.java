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

    // EMD / bid security tracking (money paid and refunded; emdAmount is the demand)
    private String emdStatus;
    private String emdPaidAmount;
    private String emdPaidDate;
    private String emdPaymentMode;
    private String emdReference;
    private String emdPaidFromAccount;
    private String emdBeneficiaryName;
    private String emdBeneficiaryBank;
    private String emdBeneficiaryAccount;
    private String emdBeneficiaryIfsc;
    private String emdValidTill;
    private String emdRefundAmount;
    private String emdRefundDate;
    private String emdRefundReference;
    private String emdRefundAccount;
    private String emdNotes;

    // tender / processing fee (separate from EMD)
    private String feeAmount;
    private String feeRefundable;
    private String feeBeneficiaryName;
    private String feeBeneficiaryBank;
    private String feeBeneficiaryAccount;
    private String feeBeneficiaryIfsc;

    private String projectId;

    // response-only
    private String createdAt;

    // source-PDF metadata (response-only; bytes never travel in JSON — they ride
    // the multipart upload/download endpoints)
    private String sourcePdfName;
    private String sourcePdfMimeType;
    private Boolean hasSourcePdf;

    // Request-only, never stored as columns: set when this save applies an Excel
    // import, so the service can record it in the tender's history.
    private String importSource;
    private String importFileName;
    private String importSummary;
    /**
     * One entry per table whose unverified rows were confirmed with the bulk tick: {table, rows}.
     * Write-only: never sent back, so a client that echoes the tender on its next
     * save (and turns nulls into "") cannot send a string where a list belongs.
     */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private List<java.util.Map<String, Object>> importBulkAcks;

    // child collections
    private List<TenderBoqItemWrapper> boqItems;
    private List<TenderEligibilityWrapper> eligibilityCriteria;
    private List<TenderDocumentWrapper> documents;
    private List<TenderDocRequestWrapper> docRequests;
    private List<TenderApprovalLogWrapper> approvalLog;
}
