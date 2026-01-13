package com.istlgroup.istl_group_crm_backend.controller;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.service.BillService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillStatsDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PaymentDTO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bills")
@RequiredArgsConstructor
@Slf4j
public class BillController {
    
    private final BillService billService;
    
    /**
     * Get all bills with role-based filtering
     * GET /api/bills
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getBills(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long poId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "billDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestHeader("X-User-Role") String userRole
    ) {
        try {
            boolean isAdmin = "ADMIN".equalsIgnoreCase(userRole) || 
                            "SUPERADMIN".equalsIgnoreCase(userRole);
            
            Page<BillDTO> bills = billService.getBills(
                    projectId, groupId, subGroupId, status, vendorId, poId,
                    search, page, size, sortBy, sortDirection, isAdmin
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("bills", bills.getContent());
            response.put("currentPage", bills.getNumber());
            response.put("totalPages", bills.getTotalPages());
            response.put("totalItems", bills.getTotalElements());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching bills", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    /**
     * Get bill by ID
     * GET /api/bills/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getBillById(@PathVariable Long id) {
        try {
            BillDTO bill = billService.getBillById(id);
            return ResponseEntity.ok(bill);
        } catch (RuntimeException e) {
            log.error("Error fetching bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error fetching bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Create new bill
     * POST /api/bills
     */
    @PostMapping
    public ResponseEntity<?> createBill(
            @RequestBody BillDTO billDTO,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO createdBill = billService.createBill(billDTO, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdBill);
        } catch (RuntimeException e) {
            log.error("Error creating bill", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating bill", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Update bill
     * PUT /api/bills/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateBill(
            @PathVariable Long id,
            @RequestBody BillDTO billDTO,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO updatedBill = billService.updateBill(id, billDTO, userId);
            return ResponseEntity.ok(updatedBill);
        } catch (RuntimeException e) {
            log.error("Error updating bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Delete bill (soft delete)
     * DELETE /api/bills/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBill(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            billService.deleteBill(id, userId);
            return ResponseEntity.ok(Map.of("message", "Bill deleted successfully"));
        } catch (RuntimeException e) {
            log.error("Error deleting bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Upload bill file
     * POST /api/bills/{id}/upload
     */
    @PostMapping("/{id}/upload")
    public ResponseEntity<?> uploadBillFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            String filePath = billService.uploadBillFile(id, file, userId);
            return ResponseEntity.ok(Map.of(
                    "message", "File uploaded successfully",
                    "filePath", filePath
            ));
        } catch (RuntimeException e) {
            log.error("Error uploading file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Error uploading file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File upload failed"));
        }
    }
    
    /**
     * Download bill file
     * GET /api/bills/{id}/download
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadBillFile(@PathVariable Long id) {
        try {
            BillDTO bill = billService.getBillById(id);
            
            if (bill.getBillFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            
            Path filePath = Paths.get(bill.getBillFilePath());
            Resource resource = new UrlResource(filePath.toUri());
            
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                           "attachment; filename=\"" + bill.getBillFileName() + "\"")
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("Error downloading file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * View bill file (inline)
     * GET /api/bills/{id}/view
     */
    @GetMapping("/{id}/view")
    public ResponseEntity<Resource> viewBillFile(@PathVariable Long id) {
        try {
            BillDTO bill = billService.getBillById(id);
            
            if (bill.getBillFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            
            Path filePath = Paths.get(bill.getBillFilePath());
            Resource resource = new UrlResource(filePath.toUri());
            
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                           "inline; filename=\"" + bill.getBillFileName() + "\"")
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("Error viewing file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Add payment to bill
     * POST /api/bills/{id}/payments
     */
    @PostMapping("/{id}/payments")
    public ResponseEntity<?> addPayment(
            @PathVariable Long id,
            @RequestBody PaymentDTO paymentDTO,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO updatedBill = billService.addPayment(id, paymentDTO, userId);
            return ResponseEntity.ok(updatedBill);
        } catch (RuntimeException e) {
            log.error("Error adding payment to bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error adding payment to bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Mark bill as fully paid
     * POST /api/bills/{id}/mark-paid
     */
    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<?> markAsPaid(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO updatedBill = billService.markAsPaid(id, userId);
            return ResponseEntity.ok(updatedBill);
        } catch (RuntimeException e) {
            log.error("Error marking bill as paid: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error marking bill as paid: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Get bill statistics
     * GET /api/bills/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId
    ) {
        try {
            BillStatsDTO stats = billService.getStatistics(projectId, groupId, subGroupId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching bill statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }
}
