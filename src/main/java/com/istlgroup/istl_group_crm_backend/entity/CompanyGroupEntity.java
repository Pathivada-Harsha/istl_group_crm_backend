package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A Parent Group or a Sub Group in the Borrower Registry company hierarchy.
 *
 * <p>{@code parentGroupId} self-references this same table: {@code null} means
 * this row is a top-level Parent Group, a set value means it's a Sub Group
 * under that Parent Group. Only one level of nesting is supported — the
 * service layer rejects giving a Sub Group its own parent.
 */
@Entity
@Table(name = "company_groups")
@Data
public class CompanyGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "parent_group_id")
    private Long parentGroupId;

    /**
     * Optional master CIN for this Parent/Sub Group itself — wholly
     * independent of any company's own {@code borrowers.cin}. Never copied
     * to or from a company created under this group.
     */
    @Column(name = "cin", length = 21)
    private String cin;

    /** Optional master registered address for this Group itself, independent of any company's own. */
    @Column(name = "registered_address", columnDefinition = "TEXT")
    private String registeredAddress;

    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
