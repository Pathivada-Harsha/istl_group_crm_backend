package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.ProposalDocumentEntity;

public interface ProposalDocumentRepo extends JpaRepository<ProposalDocumentEntity, Long> {

    /**
     * Version-list row. A generated proposal carries the cover artwork and is
     * ~9 MB, so listing must never touch {@code fileData} or {@code payload}.
     */
    interface DocumentSummary {
        Long getId();
        Integer getVersion();
        String getFileName();
        Long getFileSize();
        LocalDateTime getGeneratedAt();
        String getGeneratedByName();
        /** Has a PDF already, or can be given one from the stored payload. */
        Boolean getPreviewable();
    }

    // "payload IS NOT NULL" is answered from the row header — MySQL does not fetch
    // the off-page LONGTEXT to evaluate it, so this stays blob-free.
    @Query("SELECT d.id AS id, d.version AS version, d.fileName AS fileName, "
         + "d.fileSize AS fileSize, d.generatedAt AS generatedAt, d.generatedByName AS generatedByName, "
         + "CASE WHEN d.pdfSize IS NOT NULL OR d.payload IS NOT NULL THEN TRUE ELSE FALSE END AS previewable "
         + "FROM ProposalDocumentEntity d WHERE d.proposalId = :proposalId ORDER BY d.version DESC")
    List<DocumentSummary> findSummaries(@Param("proposalId") Long proposalId);

    /**
     * Blob-free metadata for one version. ALWAYS the first query on a serve path:
     * a scalar select against a NULL column returns empty, which is
     * indistinguishable from "no such row" — resolving the row first is what lets
     * the API tell 404 (no such version) from 409 (exists, but no PDF possible).
     */
    interface DocumentMeta {
        Long getId();
        Integer getVersion();
        String getFileName();
        String getContentType();
        Long getFileSize();
        Long getPdfSize();
        LocalDateTime getGeneratedAt();
    }

    @Query("SELECT d.id AS id, d.version AS version, d.fileName AS fileName, "
         + "d.contentType AS contentType, d.fileSize AS fileSize, d.pdfSize AS pdfSize, "
         + "d.generatedAt AS generatedAt "
         + "FROM ProposalDocumentEntity d WHERE d.proposalId = :proposalId AND d.version = :version")
    Optional<DocumentMeta> findMeta(@Param("proposalId") Long proposalId,
                                    @Param("version") Integer version);

    /** The .docx bytes alone — the PDF column is deliberately not in the select. */
    @Query("SELECT d.fileData FROM ProposalDocumentEntity d WHERE d.id = :id")
    List<byte[]> findFileDataById(@Param("id") Long id);

    /** The PDF bytes alone — the ~9 MB .docx is deliberately not in the select. */
    @Query("SELECT d.pdfData FROM ProposalDocumentEntity d WHERE d.id = :id")
    List<byte[]> findPdfDataById(@Param("id") Long id);

    /** The stored generate request, for re-rendering an old version's PDF. */
    @Query("SELECT d.payload FROM ProposalDocumentEntity d WHERE d.id = :id")
    List<String> findPayloadById(@Param("id") Long id);

    /**
     * Cache a rendered PDF without loading the row. A bulk update, not entity
     * dirty-checking: loading the entity would pull the 9 MB .docx into memory and
     * then re-send it in the same UPDATE, straight into max_allowed_packet.
     *
     * <p>{@code @Transactional} sits here rather than on the calling service method
     * because the backfill runs inside a private helper — a service-level
     * annotation would be bypassed by the Spring proxy on self-invocation and this
     * would throw TransactionRequiredException.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ProposalDocumentEntity d SET d.pdfData = :pdf, d.pdfSize = :size, "
         + "d.pdfGeneratedAt = :at WHERE d.id = :id")
    int storePdf(@Param("id") Long id, @Param("pdf") byte[] pdf,
                 @Param("size") Long size, @Param("at") LocalDateTime at);

    /** Latest stored review-step payload for a proposal — prefill without the blob. */
    @Query("SELECT d.payload FROM ProposalDocumentEntity d "
         + "WHERE d.proposalId = :proposalId ORDER BY d.version DESC")
    List<String> findPayloadsByProposal(@Param("proposalId") Long proposalId, Pageable page);

    /** Latest stored payload across every proposal on a lead. */
    @Query("SELECT d.payload FROM ProposalDocumentEntity d "
         + "WHERE d.leadId = :leadId ORDER BY d.id DESC")
    List<String> findPayloadsByLead(@Param("leadId") Long leadId, Pageable page);

    @Query("SELECT COALESCE(MAX(d.version), 0) FROM ProposalDocumentEntity d WHERE d.proposalId = :proposalId")
    int maxVersion(@Param("proposalId") Long proposalId);

    @Query("SELECT COUNT(d) FROM ProposalDocumentEntity d WHERE d.proposalId = :proposalId")
    long countByProposal(@Param("proposalId") Long proposalId);

    /**
     * Which of these proposals have a generated document — i.e. which were built
     * inside the CRM. One scalar query for a whole dropdown's worth of proposals,
     * rather than a countByProposal each; selects only the id, so no blob is touched.
     */
    @Query("SELECT DISTINCT d.proposalId FROM ProposalDocumentEntity d WHERE d.proposalId IN :proposalIds")
    List<Long> findProposalIdsWithDocuments(@Param("proposalIds") java.util.Collection<Long> proposalIds);

    /**
     * Drop one version. A bulk delete rather than {@code deleteById}, which would
     * first SELECT the row and so pull the ~9 MB .docx and its PDF into memory
     * just to throw them away — the same reason nothing else here returns entities.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ProposalDocumentEntity d WHERE d.id = :id")
    int deleteRowById(@Param("id") Long id);

    // NOTE: there are deliberately no entity-returning finders here. @Basic(LAZY)
    // on the blob columns is a no-op without bytecode enhancement, so any such
    // finder loads ~10 MB of .docx + PDF. Use findMeta + the byte accessors above.
}
