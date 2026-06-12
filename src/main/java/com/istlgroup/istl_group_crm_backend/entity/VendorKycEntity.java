package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Stores KYC document metadata + binary file content for each vendor.
 * One row per document type per vendor.
 * File bytes stored as LONGBLOB directly in the DB (same pattern as InvoiceAttachmentEntity).
 */
@Entity
@Table(
    name = "vendor_kyc_documents",
    indexes = {
        @Index(name = "idx_vendor_kyc_vendor_id", columnList = "vendor_id"),
        @Index(name = "idx_vendor_kyc_doc_type",  columnList = "doc_type")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_vendor_kyc_vendor_doc", columnNames = {"vendor_id", "doc_type"})
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorKycEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to vendors.id */
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    /**
     * Document type key — matches the frontend KYC_DOCUMENTS id:
     *   gst_certificate | pan_card | incorporation_certificate |
     *   cancelled_cheque | msme_certificate | trade_licence | iso_certificate
     */
    @Column(name = "doc_type", nullable = false, length = 60)
    private String docType;

    /** Document number entered by user (GSTIN, PAN, CIN, account no., etc.) */
    @Column(name = "doc_number", length = 50)
    private String docNumber;

    /** Original filename as uploaded */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /** MIME type of the uploaded file (e.g. application/pdf, image/jpeg) */
    @Column(name = "file_type", length = 100)
    private String fileType;

    /**
     * Raw file bytes stored directly in the database as LONGBLOB.
     * Fetched LAZILY so list queries don't load every document's binary.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_data", columnDefinition = "LONGBLOB")
    private byte[] fileData;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt  = LocalDateTime.now();
        updatedAt  = LocalDateTime.now();
        uploadedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}