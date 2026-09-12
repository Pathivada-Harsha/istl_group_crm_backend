// InfrastructureMasterListVersionEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One immutable snapshot of the Government of India PPP India "Harmonized
 * Master List of Infrastructure sub-sectors". A new row is inserted only
 * when {@link InfrastructureMasterListVersionEntity#documentHash} changes
 * from the current row's hash. {@code isCurrent} marks the single row whose
 * categories/sub-categories drive {@code technology_groups}/
 * {@code technology_sub_groups}; every other row is retained for audit only.
 */
@Entity
@Table(name = "infrastructure_master_list_version")
@Data
public class InfrastructureMasterListVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_label", nullable = false, length = 200)
    private String versionLabel;

    @Column(name = "notification_number", length = 100)
    private String notificationNumber;

    @Column(name = "notification_date")
    private LocalDate notificationDate;

    @Column(name = "source_page_url", length = 500)
    private String sourcePageUrl;

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @Column(name = "document_hash", nullable = false, columnDefinition = "CHAR(64)")
    private String documentHash;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent = false;
}
