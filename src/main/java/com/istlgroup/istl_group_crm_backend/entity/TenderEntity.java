package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Root tender record. Child collections (BOQ items, eligibility criteria,
 * documents, doc-requests, approval-log entries) are stored in their own tables
 * and reference this row by a plain {@code tender_id} column (loose-FK pattern,
 * mirroring OrderBook). Field names mirror the frontend tenderData.js shape.
 */
@Entity
@Table(name = "tenders")
@Data
public class TenderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── identification ──
    @Column(name = "tender_number")
    private String tenderNumber;

    // Government NIT titles are full sentences — the KPTCL sub-station tender runs
    // ~440 chars ("Establishing 2x20MVA, 110/11kV Sub-Station at Gholanoor and
    // construction of 110kV LILO line …"). The default varchar(255) truncated the
    // insert; these lengths must match the widened columns in the DB.
    @Column(name = "tender_name", length = 1000)
    private String tenderName;

    @Column(name = "issuing_authority", length = 500)
    private String issuingAuthority;

    // ── client / developer KYC ──
    @Column(name = "client_company", length = 500)
    private String clientCompany;

    @Column(name = "client_type")
    private String clientType;

    @Column(name = "client_gstin")
    private String clientGstin;

    @Column(name = "client_pan")
    private String clientPan;

    @Column(name = "client_cin")
    private String clientCin;

    @Column(name = "client_contact_person")
    private String clientContactPerson;

    @Column(name = "client_contact_email")
    private String clientContactEmail;

    @Column(name = "client_contact_phone")
    private String clientContactPhone;

    @Column(name = "client_address", columnDefinition = "TEXT")
    private String clientAddress;

    @Column(name = "client_city")
    private String clientCity;

    @Column(name = "client_state")
    private String clientState;

    // ── classification / location ──
    @Column(name = "sector")
    private String sector;

    @Column(name = "tender_type")
    private String tenderType;

    @Column(name = "source")
    private String source;

    @Column(name = "portal_link", columnDefinition = "TEXT")
    private String portalLink;

    @Column(name = "location", length = 500)
    private String location;

    @Column(name = "district")
    private String district;

    @Column(name = "state")
    private String state;

    @Column(name = "financial_year")
    private String financialYear;

    // ── financials ──
    @Column(name = "estimated_value", precision = 18, scale = 2)
    private BigDecimal estimatedValue;

    @Column(name = "emd_amount", precision = 18, scale = 2)
    private BigDecimal emdAmount;

    @Column(name = "performance_security_pct", precision = 12, scale = 2)
    private BigDecimal performanceSecurityPct;

    // ── key dates ──
    @Column(name = "submission_deadline")
    private LocalDate submissionDeadline;

    @Column(name = "technical_opening_date")
    private LocalDate technicalOpeningDate;

    @Column(name = "financial_opening_date")
    private LocalDate financialOpeningDate;

    // ── eligibility (cached roll-up; criteria live in the child table) ──
    @Column(name = "eligibility_decision")
    private String eligibilityDecision = "PENDING";

    // ── rate analysis & bid (global markup knobs) ──
    @Column(name = "overhead_pct", precision = 12, scale = 2)
    private BigDecimal overheadPct = BigDecimal.ZERO;

    @Column(name = "profit_pct", precision = 12, scale = 2)
    private BigDecimal profitPct = BigDecimal.ZERO;

    // ── workflow: go/no-go ──
    @Column(name = "go_no_go")
    private String goNoGo;

    @Column(name = "go_no_go_reason", columnDefinition = "TEXT")
    private String goNoGoReason;

    @Column(name = "go_no_go_date")
    private LocalDate goNoGoDate;

    // ── workflow: CFO / MD approvals ──
    @Column(name = "cfo_approval_status")
    private String cfoApprovalStatus = "pending";

    @Column(name = "cfo_approval_remarks", columnDefinition = "TEXT")
    private String cfoApprovalRemarks;

    @Column(name = "cfo_approval_date")
    private LocalDate cfoApprovalDate;

    @Column(name = "md_approval_status")
    private String mdApprovalStatus = "pending";

    @Column(name = "md_approval_remarks", columnDefinition = "TEXT")
    private String mdApprovalRemarks;

    @Column(name = "md_approval_date")
    private LocalDate mdApprovalDate;

    // ── submission (single reference — shared with the workflow ready stage) ──
    @Column(name = "submission_mode")
    private String submissionMode;

    @Column(name = "submission_reference")
    private String submissionReference;

    @Column(name = "submission_date")
    private LocalDate submissionDate;

    @Column(name = "submitted_by")
    private String submittedBy;

    // ── result ──
    @Column(name = "l1_value", precision = 18, scale = 2)
    private BigDecimal l1Value;

    @Column(name = "our_rank")
    private String ourRank;

    @Column(name = "status")
    private String status = "Draft";

    @Column(name = "loss_reason")
    private String lossReason;

    @Column(name = "result")
    private String result;

    @Column(name = "competitor_notes", columnDefinition = "TEXT")
    private String competitorNotes;

    @Column(name = "contract_value", precision = 18, scale = 2)
    private BigDecimal contractValue;

    @Column(name = "loa_number")
    private String loaNumber;

    @Column(name = "loa_date")
    private LocalDate loaDate;

    @Column(name = "agreement_date")
    private LocalDate agreementDate;

    // linked project (UI indicator only for now; String to match the frontend)
    @Column(name = "project_id")
    private String projectId;

    // ── source PDF (uploaded NIT/tender document; stored as BLOB, mirroring
    //    OrderBook's PO-file convention — bytes on the row, no disk write) ──
    @Lob
    @Column(name = "source_pdf_data", columnDefinition = "LONGBLOB")
    private byte[] sourcePdfData;

    @Column(name = "source_pdf_name", length = 500)
    private String sourcePdfName;

    @Column(name = "source_pdf_mime_type", length = 100)
    private String sourcePdfMimeType;

    @Column(name = "source_pdf_size")
    private Long sourcePdfSize;

    // ── audit ──
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
