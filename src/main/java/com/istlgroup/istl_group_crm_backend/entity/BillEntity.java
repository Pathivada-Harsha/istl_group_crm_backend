package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Formula;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bills")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillEntity {
    
    public static Object BillStatus;

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "bill_no", unique = true, length = 120)
    private String billNo;
    
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;
    
    @Column(name = "po_id")
    private Long poId;
    
    @Column(name = "bill_date", nullable = false)
    private LocalDate  billDate;
    
    @Column(name = "due_date")
    private LocalDate  dueDate;
    
    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    
    @Column(name = "paid_amount", precision = 18, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;
    
    // Generated column - automatically calculated by database
    @Formula("(total_amount - paid_amount)")
    @Column(name = "balance_amount", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal balanceAmount;
    
    // Changed from ENUM to VARCHAR
    @Column(name = "status", length = 50)
    private String status = "Pending";
    
    @Column(name = "uploaded_by")
    private Long uploadedBy;
    
    @Column(name = "uploaded_on", nullable = false)
    private LocalDateTime uploadedOn;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @Column(name = "project_id", length = 225)
    private String projectId;
    
    @Column(name = "group_id", length = 225)
    private String groupId;
    
    @Column(name = "sub_group_id", length = 225)
    private String subGroupId;
    
    // Additional tracking fields
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_by")
    private Long updatedBy;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    // File attachment
    @Column(name = "bill_file_path", length = 500)
    private String billFilePath;
    
    @Column(name = "bill_file_name", length = 255)
    private String billFileName;
    
    @Column(name = "bill_file_size")
    private Long billFileSize;
    
    // Relationships
    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BillItemEntity> items = new ArrayList<>();
    
    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BillPaymentEntity> payments = new ArrayList<>();
    
    // Transient fields for joins
    @Transient
    private String vendorName;
    
    @Transient
    private String poNumber;
    
    @Transient
    private String uploadedByName;
    
    @Transient
    private String quotationId;
    
    // Helper methods
    public void addItem(BillItemEntity item) {
        items.add(item);
        item.setBill(this);
    }
    
    public void removeItem(BillItemEntity item) {
        items.remove(item);
        item.setBill(null);
    }
    
    public void addPayment(BillPaymentEntity payment) {
        payments.add(payment);
        payment.setBill(this);
    }
    
    public void recalculateStatus() {
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.status = "Pending";
        } else if (paidAmount.compareTo(totalAmount) >= 0) {
            this.status = "Paid";
        } else {
            this.status = "Partially Paid";
        }
    }
}