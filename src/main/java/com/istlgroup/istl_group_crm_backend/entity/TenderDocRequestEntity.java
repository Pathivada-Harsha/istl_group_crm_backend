package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A workflow-side document request (Workflow tab → Document Collection). Tracks
 * requested → received → verified per department. Separate owner from the
 * Documents-tab checklist, so the two never clobber each other.
 */
@Entity
@Table(name = "tender_doc_requests")
@Data
public class TenderDocRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "sort_no")
    private Integer sortNo = 0;

    @Column(name = "label")
    private String label;

    @Column(name = "department")
    private String department;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status")
    private String status = "requested";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
