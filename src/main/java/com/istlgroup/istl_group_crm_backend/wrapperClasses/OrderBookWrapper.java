package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class OrderBookWrapper {
    private Long id;
    private String orderBookNo;

    // Customer & Proposal Reference
    private Long customerId;
    private String customerCode;
    private String customerName;
    private Long proposalId;
    private String proposalNo;
    private Long leadId;
    private String leadCode;

    // Grouping
    private String groupName;
    private String subGroupName;
    private String projectId;

    // Order Details
    private String orderTitle;
    private String orderDescription;
    private String orderDate;
    private String expectedDeliveryDate;

    // PO Details
    private String poNumber;
    private String poDate;

    // File metadata only — no binary data in the list/wrapper response
    // Frontend uses:  GET /order-book/{id}/download-po  to fetch the file
    private String poFileName;
    private String poFileMimeType;
    private boolean hasPoFile;          // true if a file is stored in DB

    // Financial
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private BigDecimal advanceAmount;
    private BigDecimal balanceAmount;

    // Status
    private String status;

    // Notes
    private String remarks;

    // Audit
    private Long createdBy;
    private String createdByName;
    private Long approvedBy;
    private String approvedByName;
    private String approvedAt;
    private String createdAt;
    private String updatedAt;
}