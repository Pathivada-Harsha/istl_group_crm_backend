package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillDTO {
    
    private Long id;
    private String billNo;
    private String billRefId;
    
    // Vendor details
    private Long vendorId;
    private String vendorName;
    
    // PO and Quotation linking
    private Long poId;
    private String poNumber;
    private String poRefId;
    private String quotationId;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate billDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;
    
    // Amounts
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal balanceAmount;
    
    // Status
    private String status;
    
    // Project hierarchy
    private String projectId;
    private String groupId;
    private String subGroupId;
    
    // File attachment
    private String billFilePath;
    private String billFileName;
    private Long billFileSize;
    
    // Notes
    private String notes;
    
    // Upload tracking
    private Long uploadedBy;
    private String uploadedByName;
    private LocalDateTime uploadedOn;
    
    // Audit fields
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    
    // ── Warehouse-outward fields ─────────────────────────────────────────────
    /** VENDOR (default) or WAREHOUSE */
    private String sourceType;
    /** Warehouse id when sourceType=WAREHOUSE */
    private Long warehouseId;
    /** Warehouse name (enriched at read time) */
    private String warehouseName;
    /** INV-TXN reference that triggered auto-creation */
    private String invTxnRef;
    
    // Line items
    private List<BillItemDTO> items = new ArrayList<>();
    
    // Payment history
    private List<PaymentHistoryDTO> paymentHistory = new ArrayList<>();
}