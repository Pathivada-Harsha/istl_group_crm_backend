package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A row in the tender's document checklist (Documents tab). Sole owner of the
 * checklist — the workflow tab tracks department asks separately in
 * {@link TenderDocRequestEntity}. Links are plain pasted URLs (no file storage).
 */
@Entity
@Table(name = "tender_documents")
@Data
public class TenderDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "sort_no")
    private Integer sortNo = 0;

    @Column(name = "document_name")
    private String documentName;

    @Column(name = "status")
    private String status = "pending";

    @Column(name = "link", columnDefinition = "TEXT")
    private String link;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
