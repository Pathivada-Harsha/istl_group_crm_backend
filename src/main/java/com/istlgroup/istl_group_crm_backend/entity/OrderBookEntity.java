package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "order_book")
@Data
public class OrderBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_book_no", unique = true, nullable = false)
    private String orderBookNo;

    // Customer & Proposal Reference
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "proposal_id")
    private Long proposalId;

    @Column(name = "lead_id")
    private Long leadId;

    // Grouping
    @Column(name = "group_name")
    private String groupName;

    @Column(name = "sub_group_name")
    private String subGroupName;

    // Order Details
    @Column(name = "order_title", nullable = false)
    private String orderTitle;

    @Column(name = "order_description", columnDefinition = "TEXT")
    private String orderDescription;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    // PO Details
    @Column(name = "po_number")
    private String poNumber;

    @Column(name = "po_date")
    private LocalDate poDate;

    // ── File stored as BLOB in the database ───────────────────────────────
    // po_file_path column is DROPPED by the migration SQL.
    // po_file_data  (LONGBLOB) holds the raw bytes.
    // po_file_name  (VARCHAR)  holds the original filename, e.g. "invoice.pdf"
    // po_file_mime_type (VARCHAR) holds Content-Type, e.g. "application/pdf"
    @Lob
    @Column(name = "po_file_data", columnDefinition = "LONGBLOB")
    private byte[] poFileData;

    @Column(name = "po_file_name")
    private String poFileName;

    @Column(name = "po_file_mime_type", length = 100)
    private String poFileMimeType;
    // ─────────────────────────────────────────────────────────────────────

    // Financial
    @Column(name = "subtotal", precision = 18, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", precision = 18, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "advance_amount", precision = 18, scale = 2)
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    @Column(name = "balance_amount", precision = 18, scale = 2)
    private BigDecimal balanceAmount = BigDecimal.ZERO;

    // Status
    @Column(name = "status")
    private String status = "Draft";

    // Notes
    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    // Audit
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

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