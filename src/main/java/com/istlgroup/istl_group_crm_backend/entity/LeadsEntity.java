package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name="leads")
@Data
public class LeadsEntity {
	  @Id
	    @GeneratedValue(strategy = GenerationType.IDENTITY)
	    private Long id;

	    @Column(name = "lead_code", unique = true)
	    private String leadCode;

	    @Column(name = "customer_id")
	    private Long customerId;

	    @Column(name = "name", nullable = false)
	    private String name;

	    @Column(name = "email")
	    private String email;

	    @Column(name = "phone")
	    private String phone;

	    @Column(name = "source")
	    private String source;

	    @Column(name = "priority")
	    private String priority;

	    @Column(name = "status")
	    private String status;

	    @Column(name = "assigned_to")
	    private Long assignedTo;

	    @Column(name = "enquiry", columnDefinition = "TEXT")
	    private String enquiry;

	    @Column(name = "group_name")
	    private String groupName;

	    @Column(name = "sub_group_name")
	    private String subGroupName;

	    @Column(name = "created_by")
	    private Long createdBy;

	    @Column(name = "created_at", updatable = false)
	    private LocalDateTime createdAt;

	    @Column(name = "updated_at")
	    private LocalDateTime updatedAt;

	    @Column(name = "deleted_at")
	    private LocalDateTime deletedAt;

	    /* ================= Lifecycle ================= */

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
