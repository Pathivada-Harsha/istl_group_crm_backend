package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Stores the invoice file uploaded by the Accounts team when approving an invoice.
 * The file content is stored as a BLOB directly in the database.
 */
@Entity
@Table(name = "invoice_attachments", indexes = {
    @Index(name = "idx_ia_invoice_id", columnList = "invoice_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", nullable = false, length = 100)
    private String fileType;

    /** The actual file stored as binary in DB */
    @Column(name = "file_data", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] fileData;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_by_name", length = 200)
    private String uploadedByName;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "notes", length = 500)
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }
}