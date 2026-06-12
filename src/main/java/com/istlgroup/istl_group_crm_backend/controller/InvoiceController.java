package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.ReceiptEntity;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.service.InvoiceService;
import com.istlgroup.istl_group_crm_backend.service.ReceiptService;
import com.istlgroup.istl_group_crm_backend.service.CustomersService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
@Slf4j
public class InvoiceController {
    
    private final InvoiceService invoiceService;
    private final CustomersService customerService;
    private final ReceiptService receiptService;
    private final CustomersRepo customersRepo;
    @GetMapping
    public ResponseEntity<?> getInvoices(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "invoiceDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestHeader(value = "x-user-id", required = false) Long userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            Page<InvoiceEntity> invoices = invoiceService.getInvoices(
                    groupId, subGroupId, projectId, status, searchTerm,
                    userId, userRole, page, size, sortBy, sortDirection,
                    fromDate, toDate
            );
           
            Map<String, Object> response = new HashMap<>();
            
            response.put("invoices", invoices.getContent());
            response.put("currentPage", invoices.getNumber());
            response.put("totalPages", invoices.getTotalPages());
            response.put("totalElements", invoices.getTotalElements());
            response.put("pageSize", invoices.getSize());
         
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching invoices", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    // =========================================================================
    // OUTSTANDINGS — dedicated endpoint for OutstandingsTab
    // No pagination, no nullable-sort, returns all + outstanding split.
    // Must be declared BEFORE /{id} so Spring doesn't treat "outstandings" as an id.
    // =========================================================================

    @GetMapping("/outstandings")
    public ResponseEntity<?> getOutstandings(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestHeader(value = "x-user-id",   required = false) Long userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            Map<String, Object> data = invoiceService.getOutstandings(
                    projectId, groupId, subGroupId, userId, userRole);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching invoice outstandings", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getInvoiceById(@PathVariable Long id) {
        try {
            InvoiceEntity invoice = invoiceService.getInvoiceByIdWithItems(id);
            return ResponseEntity.ok(invoice);
        } catch (Exception e) {
            log.error("Error fetching invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @GetMapping("/customer-by-project/{projectId}")
    public ResponseEntity<?> getCustomerByProjectId(@PathVariable String projectId) {
        try {
            CustomersEntity customer = customerService.getCustomerByProjectId(projectId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("customerId", customer.getId());
            response.put("customerCode", customer.getCustomerCode());
            response.put("name", customer.getName());
            response.put("companyName", customer.getCompanyName());
            response.put("contactPerson", customer.getContactPerson());
            response.put("email", customer.getEmail());
            response.put("phone", customer.getPhone());
            response.put("address", customer.getAddress());
            response.put("city", customer.getCity());
            response.put("state", customer.getState());
            response.put("pincode", customer.getPincode());
            response.put("gstNumber", customer.getGstNumber());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching customer for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    /**
     * GET /api/invoices/order-book-items-by-customer/{customerId}
     * Get all order book items for a customer
     */
    @GetMapping("/order-book-items-by-customer/{customerId}")
    public ResponseEntity<?> getOrderBookItemsByCustomer(@PathVariable Long customerId) {
        try {
            List<Map<String, Object>> items = invoiceService.getOrderBookItemsByCustomer(customerId);
            return ResponseEntity.ok(Map.of("success", true, "data", items));
        } catch (Exception e) {
            log.error("Error fetching order book items for customer: {}", customerId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    @PostMapping
    public ResponseEntity<?> createInvoice(
            @RequestBody InvoiceEntity invoice,
            @RequestHeader(value = "x-user-id", required = false) Long userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            if (invoice.getCustomerId() == null) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Customer ID is required"));
            }
            
            if (invoice.getItems() == null || invoice.getItems().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("At least one item is required"));
            }
            
            InvoiceEntity created = invoiceService.createInvoice(invoice, userId, userRole);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse("Invoice created successfully", created));
            
        } catch (Exception e) {
            log.error("Error creating invoice", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * POST /invoices/{id}/approve
     * Accounts team approves a PENDING_APPROVAL invoice and uploads the file.
     * Accepts multipart/form-data with a "file" part and optional "notes" field.
     */
    @PostMapping(value = "/{id}/approve", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> approveInvoice(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "notes", required = false, defaultValue = "") String notes,
            @RequestParam(value = "tallyNumber", required = false, defaultValue = "") String tallyNumber,
            @RequestHeader(value = "x-user-id",   required = false) Long   userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-name", required = false) String userName
    ) {
        try {
            // Only accounts-role users may approve
            if (userRole == null
                    || (!userRole.toUpperCase().startsWith("ACCOUNTS_")
                        && !"ADMIN".equalsIgnoreCase(userRole)
                        && !"SUPERADMIN".equalsIgnoreCase(userRole))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Only Accounts team members can approve invoices"));
            }

            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Invoice file is required for approval"));
            }

            String approverName = (userName != null && !userName.isBlank()) ? userName : "Accounts Team";

            InvoiceEntity approved = invoiceService.approveInvoice(
                    id,
                    file.getBytes(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    userId,
                    approverName,
                    notes,
                    tallyNumber.isBlank() ? null : tallyNumber.trim()
            );

            return ResponseEntity.ok(createSuccessResponse("Invoice approved successfully", approved));

        } catch (Exception e) {
            log.error("Error approving invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * POST /invoices/{id}/reject
     * Accounts team rejects a PENDING_APPROVAL invoice. Accepts an optional
     * JSON body { "reason": "..." }. The creator is notified by email + in-app.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectInvoice(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "x-user-id",   required = false) Long   userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-name", required = false) String userName
    ) {
        try {
            // Only accounts-role users may reject
            if (userRole == null
                    || (!userRole.toUpperCase().startsWith("ACCOUNTS_")
                        && !"ADMIN".equalsIgnoreCase(userRole)
                        && !"SUPERADMIN".equalsIgnoreCase(userRole))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Only Accounts team members can reject invoices"));
            }

            String approverName = (userName != null && !userName.isBlank()) ? userName : "Accounts Team";
            String reason = (body != null) ? body.getOrDefault("reason", "") : "";

            InvoiceEntity rejected = invoiceService.rejectInvoice(id, userId, approverName, reason);
            return ResponseEntity.ok(createSuccessResponse("Invoice rejected", rejected));

        } catch (Exception e) {
            log.error("Error rejecting invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * GET /invoices/{id}/download-attachment
     * Download the file uploaded by the accounts team for an APPROVED invoice.
     */
    @GetMapping("/{id}/download-attachment")
    public ResponseEntity<?> downloadAttachment(@PathVariable Long id) {
        try {
            com.istlgroup.istl_group_crm_backend.entity.InvoiceAttachmentEntity attachment =
                    invoiceService.getAttachment(id);

            HttpHeaders headers = new HttpHeaders();
            String contentType = attachment.getFileType() != null ? attachment.getFileType() : "application/octet-stream";
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentDispositionFormData("attachment", attachment.getFileName());
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

            return new ResponseEntity<>(attachment.getFileData(), headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Error downloading attachment for invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("Attachment not found: " + e.getMessage()));
        }
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<?> updateInvoice(
            @PathVariable Long id,
            @RequestBody InvoiceEntity invoice
    ) {
        try {
            InvoiceEntity updated = invoiceService.updateInvoice(id, invoice);
            return ResponseEntity.ok(createSuccessResponse("Invoice updated successfully", updated));
        } catch (Exception e) {
            log.error("Error updating invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateInvoiceStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload
    ) {
        try {
            String newStatus = payload.get("status");
            if (newStatus == null || newStatus.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Status is required"));
            }
            
            InvoiceEntity updated = invoiceService.updateStatus(id, newStatus);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Invoice status updated to: " + newStatus,
                    updated
            ));
        } catch (Exception e) {
            log.error("Error updating invoice status: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @PostMapping("/{id}/payment")
    public ResponseEntity<?> recordPayment(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());
            String method = payload.getOrDefault("method", "Bank Transfer").toString();
            String transactionRef = payload.getOrDefault("transactionReference", "").toString();
            String notes = payload.getOrDefault("notes", "").toString();
            
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Payment amount must be greater than zero"));
            }
            
            InvoiceEntity updated = invoiceService.recordPayment(id, amount, method, transactionRef, notes, userId);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Payment recorded successfully",
                    updated
            ));
        } catch (Exception e) {
            log.error("Error recording payment for invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @GetMapping("/{id}/payment-history")
    public ResponseEntity<?> getPaymentHistory(@PathVariable Long id) {
        try {
            List<PaymentHistoryEntity> history = invoiceService.getPaymentHistory(id);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Error fetching payment history for invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteInvoice(@PathVariable Long id) {
        try {
            invoiceService.deleteInvoice(id);
            return ResponseEntity.ok(createSuccessResponse("Invoice deleted successfully", null));
        } catch (Exception e) {
            log.error("Error deleting invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics() {
        try {
            InvoiceService.InvoiceStats stats = invoiceService.getStatistics();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching invoice stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    @GetMapping("/{id}/download-pdf")
    public ResponseEntity<?> downloadInvoicePdf(@PathVariable Long id) {
        try {
            InvoiceEntity invoice = invoiceService.getInvoiceByIdWithItems(id);
            byte[] pdfBytes = invoiceService.generatePdf(invoice);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", 
                "Invoice-" + invoice.getInvoiceNo() + ".pdf");
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
            
            return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Error generating PDF for invoice: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to generate PDF: " + e.getMessage()));
        }
    }
    
    private Map<String, Object> createSuccessResponse(String message, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        if (data != null) {
            response.put("data", data);
        }
        return response;
    }
    
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
    
    
    @GetMapping("/summary")
    public ResponseEntity<?> getInvoiceSummary(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestHeader(value = "x-user-id",   required = false) Long   userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            Map<String, Object> summary = invoiceService.getInvoiceSummary(
                    groupId, subGroupId, projectId, userId, userRole, searchTerm, status, fromDate, toDate
            );
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error fetching invoice summary", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
 // Add to InvoiceController.java

    @PostMapping("/receipts")
    public ResponseEntity<?> createReceipt(
            @RequestBody ReceiptEntity receipt,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            if (receipt.getCustomerId() == null) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Customer ID is required"));
            }
            
            if (receipt.getAmount() == null || receipt.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Amount must be greater than zero"));
            }
            
            ReceiptEntity created = receiptService.createReceipt(receipt, userId);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse("Receipt created successfully", created));
            
        } catch (Exception e) {
            log.error("Error creating receipt", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/receipts")
    public ResponseEntity<?> getReceipts(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String receiptType,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "receiptDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestHeader(value = "x-user-id", required = false) Long userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            Page<ReceiptEntity> receipts = receiptService.getReceipts(
                    groupId, subGroupId, projectId, receiptType, searchTerm, paymentMethod,
                    userId, userRole, page, size, sortBy, sortDirection,
                    fromDate, toDate
            );

            // Enrich each receipt with customer name
            receipts.getContent().forEach(receipt -> {
                if (receipt.getCustomerId() != null) {
                    customersRepo.findById(receipt.getCustomerId()).ifPresent(c -> {
                        receipt.setCustomerName(c.getName());
                        receipt.setCustomerCompanyName(c.getCompanyName());
                    });
                }
            });

            Map<String, Object> response = new HashMap<>();
            response.put("receipts", receipts.getContent());
            response.put("currentPage", receipts.getNumber());
            response.put("totalPages", receipts.getTotalPages());
            response.put("totalElements", receipts.getTotalElements());
            response.put("pageSize", receipts.getSize());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching receipts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/receipts/{id}")
    public ResponseEntity<?> getReceiptById(@PathVariable Long id) {
        try {
            ReceiptEntity receipt = receiptService.getReceiptById(id);
            if (receipt.getCustomerId() != null) {
                customersRepo.findById(receipt.getCustomerId()).ifPresent(c -> {
                    receipt.setCustomerName(c.getName());
                    receipt.setCustomerCompanyName(c.getCompanyName());
                });
            }
            return ResponseEntity.ok(receipt);
        } catch (Exception e) {
            log.error("Error fetching receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/receipts/{id}/allocate-advance")
    public ResponseEntity<?> allocateAdvance(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allocations = 
                (List<Map<String, Object>>) payload.get("allocations");
            
            if (allocations == null || allocations.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("No allocations provided"));
            }
            
            ReceiptEntity updated = receiptService.allocateAdvanceToInvoice(id, allocations, userId);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Advance allocated successfully",
                    updated
            ));
        } catch (Exception e) {
            log.error("Error allocating advance for receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/receipts/{id}/allocation-history")
    public ResponseEntity<?> getAdvanceAllocationHistory(@PathVariable Long id) {
        try {
            List<Map<String, Object>> history = receiptService.getAdvanceAllocationHistory(id);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Error fetching allocation history for receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/receipts/customer/{customerId}/unapplied-advances")
    public ResponseEntity<?> getUnappliedAdvances(@PathVariable Long customerId) {
        try {
            List<ReceiptEntity> advances = receiptService.getUnappliedAdvancesForCustomer(customerId);
            return ResponseEntity.ok(advances);
        } catch (Exception e) {
            log.error("Error fetching unapplied advances for customer: {}", customerId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/receipts/{id}")
    public ResponseEntity<?> deleteReceipt(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            receiptService.deleteReceipt(id, userId);
            return ResponseEntity.ok(createSuccessResponse("Receipt deleted successfully", null));
        } catch (Exception e) {
            log.error("Error deleting receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    @DeleteMapping("/receipts/{id}/permanent")
    public ResponseEntity<?> permanentlyDeleteReceipt(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", required = false) Long userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            // Check if user is admin
            if (!"ADMIN".equalsIgnoreCase(userRole) && !"SUPERADMIN".equalsIgnoreCase(userRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Only admins can permanently delete receipts"));
            }
            
            receiptService.permanentlyDeleteReceipt(id, userId);
            return ResponseEntity.ok(createSuccessResponse("Receipt permanently deleted", null));
        } catch (Exception e) {
            log.error("Error permanently deleting receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    @GetMapping("/receipts/deleted")
    public ResponseEntity<?> getDeletedReceipts(
            @RequestHeader(value = "x-user-id", required = false) Long userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            List<ReceiptEntity> deletedReceipts = receiptService.getDeletedReceipts(userId, userRole);
            return ResponseEntity.ok(deletedReceipts);
        } catch (Exception e) {
            log.error("Error fetching deleted receipts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    @PostMapping("/receipts/{id}/restore")
    public ResponseEntity<?> restoreReceipt(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            ReceiptEntity restored = receiptService.restoreReceipt(id, userId);
            return ResponseEntity.ok(createSuccessResponse("Receipt restored successfully", restored));
        } catch (Exception e) {
            log.error("Error restoring receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    @GetMapping("/receipts/summary")
    public ResponseEntity<?> getReceiptSummary(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String receiptType,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestHeader(value = "x-user-id",   required = false) Long   userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole
    ) {
        try {
            Map<String, Object> summary = receiptService.getReceiptSummary(
                    groupId, subGroupId, projectId, userId, userRole, searchTerm, receiptType, paymentMethod, fromDate, toDate
            );
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error fetching receipt summary", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

 // Add to InvoiceController.java

    /**
     * Get unpaid/partially paid invoices for a customer
     */
    @GetMapping("/customer/{customerId}/unpaid-invoices")
    public ResponseEntity<?> getUnpaidInvoicesForCustomer(@PathVariable Long customerId) {
        try {
            List<InvoiceEntity> invoices = invoiceService.getUnpaidInvoicesForCustomer(customerId);
            return ResponseEntity.ok(invoices);
        } catch (Exception e) {
            log.error("Error fetching unpaid invoices for customer: {}", customerId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Get unpaid/partially paid invoices by project
     */
    @GetMapping("/project/{projectId}/unpaid-invoices")
    public ResponseEntity<?> getUnpaidInvoicesByProject(@PathVariable String projectId) {
        try {
            List<InvoiceEntity> invoices = invoiceService.getUnpaidInvoicesByProject(projectId);
            return ResponseEntity.ok(invoices);
        } catch (Exception e) {
            log.error("Error fetching unpaid invoices for project: {}", projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
 // Add to InvoiceController.java

    @GetMapping("/receipts/customer/{customerId}/unapplied-advances-details")
    public ResponseEntity<?> getUnappliedAdvancesWithDetails(@PathVariable Long customerId) {
        try {
            List<Map<String, Object>> advances = receiptService.getUnappliedAdvancesWithDetails(customerId);
            return ResponseEntity.ok(advances);
        } catch (Exception e) {
            log.error("Error fetching unapplied advances details for customer: {}", customerId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
 // Add to InvoiceController.java

    /**
     * Update existing receipt
     */
    @PutMapping("/receipts/{id}")
    public ResponseEntity<?> updateReceipt(
            @PathVariable Long id,
            @RequestBody ReceiptEntity receipt,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            ReceiptEntity updated = receiptService.updateReceipt(id, receipt, userId);
            return ResponseEntity.ok(createSuccessResponse("Receipt updated successfully", updated));
        } catch (Exception e) {
            log.error("Error updating receipt: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Update advance allocation (move from one invoice to another)
     */
    @PostMapping("/receipts/{receiptId}/update-allocation")
    public ResponseEntity<?> updateAdvanceAllocation(
            @PathVariable Long receiptId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            Long oldInvoiceId = Long.valueOf(payload.get("oldInvoiceId").toString());
            Long newInvoiceId = payload.get("newInvoiceId") != null ? 
                    Long.valueOf(payload.get("newInvoiceId").toString()) : null;
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());
            
            ReceiptEntity updated = receiptService.updateAdvanceAllocation(
                    receiptId, oldInvoiceId, newInvoiceId, amount, userId
            );
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Advance allocation updated successfully",
                    updated
            ));
        } catch (Exception e) {
            log.error("Error updating advance allocation for receipt: {}", receiptId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Remove advance allocation from an invoice
     */
    @DeleteMapping("/receipts/{receiptId}/advance-allocations/{allocationId}")
    public ResponseEntity<?> removeAdvanceAllocation(
            @PathVariable Long receiptId,
            @PathVariable Long allocationId,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            receiptService.removeAdvanceAllocation(receiptId, allocationId, userId);
            return ResponseEntity.ok(createSuccessResponse("Allocation removed successfully", null));
        } catch (Exception e) {
            log.error("Error removing allocation: {}", allocationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
 // Add to InvoiceController.java

    /**
     * Get allocation details for a receipt
     */
    @GetMapping("/receipts/{receiptId}/allocations")
    public ResponseEntity<?> getAllocationDetails(@PathVariable Long receiptId) {
        try {
            List<Map<String, Object>> allocations = receiptService.getAllocationDetails(receiptId);
            return ResponseEntity.ok(allocations);
        } catch (Exception e) {
            log.error("Error getting allocation details for receipt: {}", receiptId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Edit allocation - Move from one invoice to another
     */
    @PutMapping("/receipts/{receiptId}/allocations/edit")
    public ResponseEntity<?> editAllocation(
            @PathVariable Long receiptId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            Long oldInvoiceId = Long.valueOf(payload.get("oldInvoiceId").toString());
            Long newInvoiceId = payload.get("newInvoiceId") != null ? 
                    Long.valueOf(payload.get("newInvoiceId").toString()) : null;
            BigDecimal newAmount = new BigDecimal(payload.get("newAmount").toString());
            
            ReceiptEntity updated = receiptService.editAllocation(
                    receiptId, oldInvoiceId, newInvoiceId, newAmount, userId
            );
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Allocation updated successfully",
                    updated
            ));
        } catch (Exception e) {
            log.error("Error editing allocation for receipt: {}", receiptId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Remove specific allocation
     */
    @DeleteMapping("/receipts/{receiptId}/allocations/{invoiceId}")
    public ResponseEntity<?> removeAllocation(
            @PathVariable Long receiptId,
            @PathVariable Long invoiceId,
            @RequestHeader(value = "x-user-id", required = false) Long userId
    ) {
        try {
            ReceiptEntity updated = receiptService.removeSpecificAllocation(receiptId, invoiceId, userId);
            return ResponseEntity.ok(createSuccessResponse("Allocation removed successfully", updated));
        } catch (Exception e) {
            log.error("Error removing allocation for receipt: {}, invoice: {}", receiptId, invoiceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
}