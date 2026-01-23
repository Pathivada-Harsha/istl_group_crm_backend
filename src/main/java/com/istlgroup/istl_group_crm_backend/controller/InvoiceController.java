package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.service.InvoiceService;
import com.istlgroup.istl_group_crm_backend.service.CustomersService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Slf4j
public class InvoiceController {
    
    private final InvoiceService invoiceService;
    private final CustomersService customerService;
    
    @GetMapping
    public ResponseEntity<?> getInvoices(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "invoiceDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestHeader("x-user-id") Long userId,
            @RequestHeader("x-user-role") String userRole
    ) {
        try {
            Page<InvoiceEntity> invoices = invoiceService.getInvoices(
                    groupId, subGroupId, projectId, status, searchTerm,
                    userId, userRole, page, size, sortBy, sortDirection
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
    
    @PostMapping
    public ResponseEntity<?> createInvoice(
            @RequestBody InvoiceEntity invoice,
            @RequestHeader("x-user-id") Long userId
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
            
            InvoiceEntity created = invoiceService.createInvoice(invoice, userId);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse("Invoice created successfully", created));
            
        } catch (Exception e) {
            log.error("Error creating invoice", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
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
            @RequestHeader("x-user-id") Long userId
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
}