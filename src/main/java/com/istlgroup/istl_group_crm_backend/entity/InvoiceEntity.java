// InvoiceEntity.java
package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices", indexes = {
    @Index(name = "idx_invoice_no", columnList = "invoice_no"),
    @Index(name = "idx_customer_id", columnList = "customer_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_project_id", columnList = "project_id"),
    @Index(name = "idx_group_id", columnList = "group_id"),
    @Index(name = "idx_sub_group_id", columnList = "sub_group_id"),
    @Index(name = "idx_invoice_date", columnList = "invoice_date"),
    @Index(name = "idx_due_date", columnList = "due_date"),
    @Index(name = "idx_created_by", columnList = "created_by")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", unique = true, length = 80)
    private String invoiceNo;

    /**
     * User-entered Tally / external invoice number.
     * Optional — generated invoiceNo is always present; this is an additional
     * reference number the user provides from their accounting system (e.g. Tally).
     */
    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "group_id", length = 225)
    private String groupId;

    @Column(name = "sub_group_id", length = 225)
    private String subGroupId;

    // NEW: Link to order book
    @Column(name = "order_book_id")
    private Long orderBookId;
    
    // NEW: Company selection (ISTL or SESOLA)
    @Column(name = "company", length = 50)
    private String company; // "ISTL" or "SESOLA"
    
    
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", precision = 18, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "balance_amount", precision = 18, scale = 2, insertable = false, updatable = false)
    private BigDecimal balanceAmount;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    @Transient
    private String customerName;
    
    @Transient
    private String customerCompanyName;
    // Relationships
    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference  // ← ADD THIS
    @Builder.Default
    private List<InvoiceItemEntity> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (invoiceDate == null) {
            invoiceDate = LocalDate.now();
        }
        if (status == null || status.isEmpty()) {  // FIXED TYPO
            status = Status.DRAFT;
        } else {
            // Last line of defence: never persist a raw client token like "PENDING_APPROVAL"
            status = Status.normalize(status);
        }
        if (paidAmount == null) {
            paidAmount = BigDecimal.ZERO;
        }
    }
 // Add this to InvoiceEntity.java

    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference
    @Builder.Default
    private List<PaymentHistoryEntity> paymentHistory = new ArrayList<>();
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        status = Status.normalize(status);
    }

    /**
     * Set to true by the service layer when this invoice has an entry in
     * invoice_attachments. Used by the frontend to show the Download button.
     * Not persisted — computed on read.
     */
    @Transient
    @JsonProperty("hasAttachment")
    private Boolean hasAttachment = false;

    @Transient
    private String attachmentFileName;

    // Status constants for reference
    public static class Status {
        public static final String DRAFT            = "Draft";
        public static final String SENT             = "Sent";
        public static final String PARTIALLY_PAID   = "Partially Paid";
        public static final String PAID             = "Paid";
        public static final String CANCELLED        = "Cancelled";
        /** Submitted by non-accounts user — awaiting accounts team approval */
        public static final String PENDING_APPROVAL = "Pending Approval";
        /** Approved by accounts team — attachment uploaded */
        public static final String APPROVED         = "Approved";
        public static final String REJECTED         = "Rejected";

        /**
         * Canonicalises whatever status string a client sent.
         *
         * <p>The UI works in screaming-snake tokens ({@code PENDING_APPROVAL},
         * {@code SENT}, ...) while this column stores the human labels above. Both
         * spellings used to reach the DB, which broke equality checks: an invoice
         * saved as {@code "PENDING_APPROVAL"} failed the approve/reject guard with
         * the nonsensical "not in PENDING_APPROVAL status. Current status:
         * PENDING_APPROVAL", and the status filter (which upper-cases and swaps
         * underscores for spaces) missed those rows too. Everything now goes
         * through here, so only the labels above are ever persisted.
         *
         * <p>Unknown values come back trimmed but unchanged rather than dropped.
         */
        public static String normalize(String raw) {
            if (raw == null) return null;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) return trimmed;
            String key = trimmed.replace('-', '_').replace(' ', '_').toUpperCase();
            if (key.equals("DRAFT"))                                     return DRAFT;
            if (key.equals("SENT"))                                      return SENT;
            if (key.equals("PARTIALLY_PAID") || key.equals("PARTIAL"))   return PARTIALLY_PAID;
            if (key.equals("PAID"))                                      return PAID;
            if (key.equals("CANCELLED") || key.equals("CANCELED"))       return CANCELLED;
            if (key.equals("PENDING_APPROVAL") || key.equals("PENDING")) return PENDING_APPROVAL;
            if (key.equals("APPROVED"))                                  return APPROVED;
            if (key.equals("REJECTED"))                                  return REJECTED;
            return trimmed;
        }

        /** True when {@code raw} means the same status as {@code canonical}, whatever its spelling. */
        public static boolean is(String raw, String canonical) {
            String n = normalize(raw);
            return n != null && n.equalsIgnoreCase(canonical);
        }
    }
    
    // Company constants
    public static class Company {
        public static final String ISTL = "ISTL";
        public static final String SESOLA = "SESOLA";
    }
}
