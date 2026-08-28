package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One generated proposal document, kept as an immutable version against its
 * {@link ProposalsEntity}. Re-generating appends a row rather than overwriting,
 * so an already-sent file stays downloadable after the numbers move on.
 *
 * <p>{@code payload} is the review-step request that produced the file, stored so
 * a re-generate can prefill exactly what was used last time (same trick as
 * {@code purchase_orders.po_doc_payload}).
 */
@Entity
@Table(name = "proposal_documents")
@Data
public class ProposalDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "lead_id")
    private Long leadId;

    /** 1-based, per proposal. */
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * The generated .docx — the deliverable. ~9 MB, because the skeleton carries
     * the cover artwork.
     *
     * <p><b>@Basic(LAZY) here is a no-op and must not be relied on.</b> Hibernate
     * only honours lazy basic attributes on bytecode-enhanced classes, and there
     * is no hibernate-enhance-maven-plugin in the build — so loading this entity
     * ALWAYS pulls all three blobs below. The rule is therefore: never load the
     * entity on a read path. {@code ProposalDocumentRepo} exposes scalar
     * projections ({@code findMeta}, {@code findFileDataById},
     * {@code findPdfDataById}, {@code findPayloadById}) for exactly that reason,
     * and the entity is only ever constructed for the INSERT in
     * {@code SolarProposalDocService.generate()}.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_data", columnDefinition = "LONGBLOB")
    private byte[] fileData;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    /**
     * PDF rendition of this same version, rendered by {@code SolarProposalPdfService}
     * from the same token map as the .docx. Preview-only; the .docx above is the
     * artifact sent to clients.
     *
     * <p>This is a CACHE: null simply means "not rendered yet" (any row predating
     * the feature, or a render that failed). The first preview re-renders it from
     * {@link #payload} and stores it, so no backfill job is needed, and clearing
     * the column at any time is safe.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "pdf_data", columnDefinition = "LONGBLOB")
    private byte[] pdfData;

    @Column(name = "pdf_size")
    private Long pdfSize;

    @Column(name = "pdf_generated_at")
    private LocalDateTime pdfGeneratedAt;

    @Column(name = "generated_by")
    private Long generatedBy;

    @Column(name = "generated_by_name", length = 200)
    private String generatedByName;

    @Column(name = "generated_at", updatable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        this.generatedAt = LocalDateTime.now();
    }
}
