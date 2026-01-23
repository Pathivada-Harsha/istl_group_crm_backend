package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Data
public class CustomersEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "customer_code", unique = true, length = 50)
    private String customerCode;
    
    @Column(name = "name", nullable = false)
    private String name;
    
    @Column(name = "company_name")
    private String companyName;
    
    @Column(name = "group_name", length = 100)
    private String groupName;  // Changed from ENUM to VARCHAR
    
    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;
    
    @Column(name = "contact_person", length = 200)
    private String contactPerson;
    
    @Column(name = "designation", length = 100)
    private String designation;
    
    @Column(name = "email")
    private String email;
    
    @Column(name = "phone", length = 50)
    private String phone;
    
    @Column(name = "alt_phone", length = 50)
    private String altPhone;
    
    @Column(name = "website", length = 512)
    private String website;
    
    @Column(name = "gst_number", length = 50)
    private String gstNumber;
    
    @Column(name = "pan", length = 20)
    private String pan;
    
    @Column(name = "address", columnDefinition = "TEXT")
    private String address;
    
    @Column(name = "city", length = 100)
    private String city;
    
    @Column(name = "state", length = 100)
    private String state;
    
    @Column(name = "pincode", length = 20)
    private String pincode;
    
    @Column(name = "status", length = 50)
    private String status;  // Changed from ENUM to VARCHAR
    
    @Column(name = "assigned_to")
    private Long assignedTo;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null || status.isEmpty()) {
            status = "Active";
        }
        if (groupName == null || groupName.isEmpty()) {
            groupName = "Others";
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Optional: Constants for reference (not enums)
    public static class GroupNames {
        public static final String CCMS = "CCMS";
        public static final String SOLAR = "Solar";
        public static final String EPC = "EPC";
        public static final String IOT = "IoT";
        public static final String HYBRID = "Hybrid";
        public static final String OTHERS = "Others";
    }
    
    public static class CustomerStatuses {
        public static final String ACTIVE = "Active";
        public static final String INACTIVE = "Inactive";
        public static final String LEAD = "Lead";
        public static final String PROSPECT = "Prospect";
    }
}