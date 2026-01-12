// DropdownProjectEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DropdownProjectEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "project_unique_id", nullable = false, unique = true, length = 50)
    private String projectUniqueId;
    
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sub_group_id", nullable = false)
    @JsonIgnoreProperties({"projects"})
    private DropdownSubGroupEntity subGroup;
    
    @Column(name = "Lead_id")
    private String lead_id;
    
    @Column(name = "group_id")
    private String group_id;
    
    @Column(name = "sub_group_name")
    private String subGroupName;
    
    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "location", length = 200)
    private String location;
    
    @Column(name = "start_date")
    private LocalDate startDate;
    
    @Column(name = "end_date")
    private LocalDate endDate;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ProjectStatus status = ProjectStatus.PLANNING;
    
    @Column(name = "budget", precision = 15, scale = 2)
    private BigDecimal budget;
    
    @Column(name = "is_active")
    private Boolean isActive = true;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    public enum ProjectStatus {
        PLANNING, IN_PROGRESS, COMPLETED, ON_HOLD, CANCELLED
    }

	
}