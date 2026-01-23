package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "followups")
@Data
public class FollowupsEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "related_type", nullable = false)
    private String relatedType; // "LEAD", "CUSTOMER", "PROJECT"
    
    @Column(name = "related_id", nullable = false)
    private Long relatedId;
    
    @Column(name = "lead_id")
    private Long leadId;
    
    @Column(name = "customer_id")
    private Long customerId;
    
    @Column(name = "project_id")
    private Long projectId;
    
    @Column(name = "group_name")
    private String groupName;
    
    @Column(name = "sub_group_name")
    private String subGroupName;
    
    @Column(name = "followup_type")
    private String followupType; // "Call", "Email", "Meeting", "Visit", "Demo"
    
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "assigned_to")
    private Long assignedTo;
    
    @Column(name = "status")
    private String status; // "Pending", "Completed", "Cancelled", "Rescheduled"
    
    @Column(name = "priority")
    private String priority; // "High", "Medium", "Low"
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    @Column(name = "outcome", columnDefinition = "TEXT")
    private String outcome; // What happened during the follow-up
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
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