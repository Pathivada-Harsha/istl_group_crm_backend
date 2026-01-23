package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "lead_history")
@Data
public class LeadHistoryEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "lead_id", nullable = false)
    private Long leadId;
    
    @Column(name = "action_type", nullable = false)
    private String actionType; // "CREATED", "STATUS_CHANGED", "ASSIGNED", "FOLLOWUP_ADDED", "NOTE_ADDED", "PROPOSAL_SENT", "CUSTOMER_FEEDBACK"
    
    @Column(name = "field_changed")
    private String fieldChanged;
    
    @Column(name = "old_value")
    private String oldValue;
    
    @Column(name = "new_value")
    private String newValue;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}