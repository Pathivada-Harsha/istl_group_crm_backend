package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "followups")
@Data
public class FollowupsEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "related_type", nullable = false)
    private String relatedType;
    
    @Column(name = "related_id", nullable = false)
    private Long relatedId;
    
    @Column(name = "followup_type")
    private String followupType;
    
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "assigned_to")
    private Long assignedTo;
    
    @Column(name = "status")
    private String status;
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    @Column(name = "priority")
    private String priority;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "Pending";
        }
        if (priority == null) {
            priority = "Medium";
        }
        if (followupType == null) {
            followupType = "Call";
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}