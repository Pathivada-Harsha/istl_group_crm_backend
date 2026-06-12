// ReceiptService.java
package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptService {
    
    private final ReceiptRepository receiptRepository;
    private final AdvanceAllocationRepository allocationRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final CustomersRepo customerRepository;
    private final BillService billService;
    private final InvoiceService invoiceService;
    private final ProjectAccessService projectAccessService;
    private final RoleHierarchyRepo roleHierarchyRepo;
    
    /**
     * Get receipts with filtering
     */
    @Transactional(readOnly = true)
    public Page<ReceiptEntity> getReceipts(
            String groupId,
            String subGroupId,
            String projectId,
            String receiptType,
            String searchTerm,
            String paymentMethod,
            Long userId,
            String userRole,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            String fromDate,
            String toDate
    ) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy)
        );

        boolean isAdmin = isAdmin(userRole);

        // Normalize inputs
        String cleanGroup   = (groupId    != null && !groupId.trim().isEmpty())    ? groupId.trim()    : null;
        String cleanSub     = (subGroupId != null && !subGroupId.trim().isEmpty()) ? subGroupId.trim() : null;
        String cleanProject = (projectId  != null && !projectId.trim().isEmpty())  ? projectId.trim()  : null;
        String cleanSearch  = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;
        String cleanType    = (receiptType != null && !receiptType.trim().isEmpty() && !"all".equalsIgnoreCase(receiptType.trim())) ? receiptType.trim() : null;
        String cleanPayment = (paymentMethod != null && !paymentMethod.trim().isEmpty() && !"all".equalsIgnoreCase(paymentMethod.trim())) ? paymentMethod.trim() : null;

        // Parse date range (yyyy-MM-dd from frontend)
        LocalDateTime from = (fromDate != null && !fromDate.isBlank())
                ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to   = (toDate   != null && !toDate.isBlank())
                ? LocalDate.parse(toDate).atTime(23, 59, 59) : null;

        if (cleanProject != null) {
            return receiptRepository.findFiltered(null, null, cleanProject, null, cleanType, cleanPayment, cleanSearch, from, to, pageable);
        } else if (isAdmin) {
            return receiptRepository.findFiltered(cleanGroup, cleanSub, null, null, cleanType, cleanPayment, cleanSearch, from, to, pageable);
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                return receiptRepository.findFilteredAccessible(accessibleProjectIds, cleanGroup, cleanSub, cleanType, cleanPayment, cleanSearch, from, to, pageable);
            } else {
                return receiptRepository.findFiltered(cleanGroup, cleanSub, null, userId, cleanType, cleanPayment, cleanSearch, from, to, pageable);
            }
        }
    }
    
    /**
     * Get receipt by ID
     */
    @Transactional(readOnly = true)
    public ReceiptEntity getReceiptById(Long id) {
        return receiptRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receipt not found with id: " + id));
    }
    
    /**
     * Create new receipt (advance or invoice payment)
     */
    @Transactional
    public ReceiptEntity createReceipt(ReceiptEntity receipt, Long userId) {
        try {
            log.info("Creating receipt for user: {}", userId);
            
            // Receipt number is derived from the DB-generated id (see below).
            // Use a temp placeholder to satisfy NOT NULL constraint.
            receipt.setReceiptNo("__TEMP_RCP_" + System.nanoTime() + "__");
            
            // Set metadata
            receipt.setCreatedBy(userId);
            receipt.setCreatedAt(LocalDateTime.now());
            receipt.setUpdatedAt(LocalDateTime.now());
            
            // Set applied amount default
            if (receipt.getAppliedAmount() == null) {
                receipt.setAppliedAmount(BigDecimal.ZERO);
            }
            
            // Validate receipt type
            if (receipt.getReceiptType() == null) {
                throw new RuntimeException("Receipt type is required");
            }
            
            // If it's an invoice payment, apply immediately
            if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(receipt.getReceiptType())) {
                if (receipt.getInvoiceId() == null) {
                    throw new RuntimeException("Invoice ID is required for invoice payments");
                }
                
                // Apply payment to invoice
                InvoiceEntity invoice = invoiceRepository.findById(receipt.getInvoiceId())
                        .orElseThrow(() -> new RuntimeException("Invoice not found"));
                
                // Validate payment amount
                if (receipt.getAmount().compareTo(invoice.getBalanceAmount()) > 0) {
                    throw new RuntimeException("Payment amount exceeds invoice balance");
                }
                
                // Mark as fully applied
                receipt.setAppliedAmount(receipt.getAmount());
            }
            
            // Save receipt — DB assigns auto-increment id
            ReceiptEntity savedReceipt = receiptRepository.save(receipt);
            // Derive receipt number from the DB id — only if no real code exists yet.
            String existingRcpNo = savedReceipt.getReceiptNo();
            if (existingRcpNo == null || existingRcpNo.isBlank() || existingRcpNo.startsWith("__TEMP_")) {
                String receiptNo = String.format("RCP-%d-%04d",
                    java.time.Year.now().getValue(), savedReceipt.getId());
                savedReceipt.setReceiptNo(receiptNo);
                savedReceipt = receiptRepository.save(savedReceipt);
            }
            log.info("Receipt saved with ID: {} and number: {}", savedReceipt.getId(), savedReceipt.getReceiptNo());
            
            // If invoice payment, update invoice paid_amount and payment history
            if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(savedReceipt.getReceiptType())) {
                updateInvoicePayment(savedReceipt.getInvoiceId(), savedReceipt.getAmount(), 
                                   savedReceipt.getId(), userId, savedReceipt.getPaymentMethod(),
                                   savedReceipt.getTransactionReference(), savedReceipt.getNotes(), false);
                // Note: updateProjectStats=false here because the project sync below
                // covers all receipt types (ADVANCE + INVOICE_PAYMENT) in one place.
            }

            // FIX: Immediately reflect this receipt in project.paid_invoice_value.
            // This applies to BOTH advance receipts AND direct invoice payment receipts.
            // project.paid_invoice_value is now sourced from SUM(receipts.amount), so
            // syncing here means the project dashboard updates the moment cash is received —
            // not only after an advance is allocated to an invoice.
            if (savedReceipt.getProjectId() != null && !savedReceipt.getProjectId().isBlank()) {
                invoiceService.syncProjectInvoiceStats(savedReceipt.getProjectId());
                log.info("Synced project [{}] paid_invoice_value after receipt [{}] creation",
                         savedReceipt.getProjectId(), savedReceipt.getReceiptNo());
            }

            log.info("Receipt created successfully: {}", savedReceipt.getReceiptNo());
            return savedReceipt;
            
        } catch (Exception e) {
            log.error("Error creating receipt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create receipt: " + e.getMessage());
        }
    }
    
    /**
     * Allocate advance to invoice(s)
     */
    @Transactional
    public ReceiptEntity allocateAdvanceToInvoice(Long receiptId, List<Map<String, Object>> allocations, Long userId) {
        try {
            ReceiptEntity receipt = getReceiptById(receiptId);
            
            // Validate it's an advance
            if (!ReceiptEntity.ReceiptType.ADVANCE.equals(receipt.getReceiptType())) {
                throw new RuntimeException("Only advance receipts can be allocated");
            }
            
            BigDecimal totalAllocation = BigDecimal.ZERO;
            
            // Process each allocation
            for (Map<String, Object> allocation : allocations) {
                Long invoiceId = Long.valueOf(allocation.get("invoiceId").toString());
                BigDecimal amount = new BigDecimal(allocation.get("amount").toString());
                
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue; // Skip zero allocations
                }
                
                // Get invoice
                InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                        .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
                
                // Validate allocation amount
                if (amount.compareTo(invoice.getBalanceAmount()) > 0) {
                    throw new RuntimeException("Allocation amount exceeds invoice balance for invoice: " + invoice.getInvoiceNo());
                }
                
                // Create allocation record
                AdvanceAllocationEntity allocationEntity = AdvanceAllocationEntity.builder()
                        .receipt(receipt)
                        .invoiceId(invoiceId)
                        .allocatedAmount(amount)
                        .allocationDate(LocalDateTime.now())
                        .allocatedBy(userId)
                        .build();
                
                allocationRepository.save(allocationEntity);
                
                // Update invoice payment
                updateInvoicePayment(invoiceId, amount, receiptId, userId, 
                                   receipt.getPaymentMethod(), receipt.getTransactionReference(),
                                   "Advance allocation from receipt: " + receipt.getReceiptNo(), false);
                
                totalAllocation = totalAllocation.add(amount);
            }
            
            // Check if total allocation exceeds available advance
            BigDecimal newAppliedAmount = receipt.getAppliedAmount().add(totalAllocation);
            if (newAppliedAmount.compareTo(receipt.getAmount()) > 0) {
                throw new RuntimeException("Total allocation exceeds available advance amount");
            }
            
            // Update receipt applied amount
            receipt.setAppliedAmount(newAppliedAmount);
            receipt.setUpdatedAt(LocalDateTime.now());
            
            ReceiptEntity updated = receiptRepository.save(receipt);
            log.info("Allocated {} from advance {} to invoices", totalAllocation, receipt.getReceiptNo());
            
            return updated;
            
        } catch (Exception e) {
            log.error("Error allocating advance: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to allocate advance: " + e.getMessage());
        }
    }
    
    /**
     * Get advance allocation history for a receipt
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAdvanceAllocationHistory(Long receiptId) {
        List<AdvanceAllocationEntity> allocations = allocationRepository.findAllocationsByReceipt(receiptId);
        List<Map<String, Object>> history = new ArrayList<>();
        
        for (AdvanceAllocationEntity allocation : allocations) {
            InvoiceEntity invoice = invoiceRepository.findById(allocation.getInvoiceId())
                    .orElse(null);
            
            Map<String, Object> item = new HashMap<>();
            item.put("id", allocation.getId());
            item.put("invoiceId", allocation.getInvoiceId());
            item.put("invoiceNo", invoice != null ? invoice.getInvoiceNo() : "Unknown");
            item.put("allocatedAmount", allocation.getAllocatedAmount());
            item.put("allocationDate", allocation.getAllocationDate());
            item.put("allocatedBy", allocation.getAllocatedBy());
            
            history.add(item);
        }
        
        return history;
    }
    
    /**
     * Get unapplied advances for a customer
     */
    @Transactional(readOnly = true)
    public List<ReceiptEntity> getUnappliedAdvancesForCustomer(Long customerId) {
        return receiptRepository.findUnappliedAdvancesByCustomer(customerId);
    }
    
    /**
     * Delete receipt (soft delete)
     */
    @Transactional
    public void deleteReceipt(Long id) {
        ReceiptEntity receipt = getReceiptById(id);
        
        // Check if advance has been allocated
        if (ReceiptEntity.ReceiptType.ADVANCE.equals(receipt.getReceiptType()) &&
            receipt.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new RuntimeException("Cannot delete advance that has been allocated to invoices");
        }
        
        receipt.setDeletedAt(LocalDateTime.now());
        receiptRepository.save(receipt);

        // FIX: sync project — SUM(receipts.amount) will now exclude this deleted receipt
        if (receipt.getProjectId() != null && !receipt.getProjectId().isBlank()) {
            invoiceService.syncProjectInvoiceStats(receipt.getProjectId());
        }
        log.info("Soft deleted receipt: {}", receipt.getReceiptNo());
    }
    @Transactional
    public void deleteReceipt(Long id, Long userId) {
        try {
            ReceiptEntity receipt = getReceiptById(id);
            
            log.info("Attempting to delete receipt: {}", receipt.getReceiptNo());
            
            // Check if advance has been allocated
            if (ReceiptEntity.ReceiptType.ADVANCE.equals(receipt.getReceiptType()) &&
                receipt.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
                
                // Get all allocations for this receipt
                List<AdvanceAllocationEntity> allocations = allocationRepository
                        .findByReceiptIdOrderByAllocationDateDesc(receipt.getId());
                
                if (!allocations.isEmpty()) {
                    // Reverse all allocations
                    log.info("Reversing {} allocations for receipt {}", 
                            allocations.size(), receipt.getReceiptNo());
                    
                    for (AdvanceAllocationEntity allocation : allocations) {
                        reverseInvoicePayment(allocation.getInvoiceId(), 
                                            allocation.getAllocatedAmount(), 
                                            receipt.getId());
                    }
                    
                    // Delete all allocation records
                    allocationRepository.deleteAll(allocations);
                    
                    log.info("All allocations reversed for receipt {}", receipt.getReceiptNo());
                }
            }
            
            // If it's an invoice payment, reverse the payment
            if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(receipt.getReceiptType()) &&
                receipt.getInvoiceId() != null) {
                
                log.info("Reversing invoice payment for receipt {}", receipt.getReceiptNo());
                reverseInvoicePayment(receipt.getInvoiceId(), receipt.getAmount(), receipt.getId());
            }
            
            // Delete all payment history records associated with this receipt
            List<PaymentHistoryEntity> paymentHistories = paymentHistoryRepository
                    .findByReceiptId(receipt.getId());
            
            if (!paymentHistories.isEmpty()) {
                log.info("Deleting {} payment history records", paymentHistories.size());
                
                // Mark them as deleted rather than hard delete
                for (PaymentHistoryEntity history : paymentHistories) {
                    history.setNotes((history.getNotes() != null ? history.getNotes() + " | " : "") + 
                                   "DELETED - Receipt " + receipt.getReceiptNo() + " deleted by user " + userId);
                    paymentHistoryRepository.save(history);
                }
            }
            
            // Soft delete the receipt
            receipt.setDeletedAt(LocalDateTime.now());
            receiptRepository.save(receipt);

            // FIX: sync project — SUM(receipts.amount) will now exclude this deleted receipt
            if (receipt.getProjectId() != null && !receipt.getProjectId().isBlank()) {
                invoiceService.syncProjectInvoiceStats(receipt.getProjectId());
                log.info("Synced project [{}] paid_invoice_value after receipt [{}] deletion",
                         receipt.getProjectId(), receipt.getReceiptNo());
            }
            log.info("Successfully deleted receipt: {}", receipt.getReceiptNo());
            
        } catch (Exception e) {
            log.error("Error deleting receipt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete receipt: " + e.getMessage());
        }
    }
    @Transactional
    public void permanentlyDeleteReceipt(Long id, Long userId) {
        try {
            ReceiptEntity receipt = getReceiptById(id);
            
            log.warn("Attempting PERMANENT deletion of receipt: {}", receipt.getReceiptNo());
            
            // Check if receipt has any allocations or payments
            if (ReceiptEntity.ReceiptType.ADVANCE.equals(receipt.getReceiptType())) {
                List<AdvanceAllocationEntity> allocations = allocationRepository
                        .findByReceiptIdOrderByAllocationDateDesc(receipt.getId());
                
                if (!allocations.isEmpty()) {
                    throw new RuntimeException(
                        "Cannot permanently delete receipt with allocations. " +
                        "Please use soft delete or remove all allocations first."
                    );
                }
            }
            
            if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(receipt.getReceiptType()) &&
                receipt.getInvoiceId() != null) {
                throw new RuntimeException(
                    "Cannot permanently delete invoice payment receipt. " +
                    "This would cause data integrity issues."
                );
            }
            
            // Delete payment history records
            List<PaymentHistoryEntity> paymentHistories = paymentHistoryRepository
                    .findByReceiptId(receipt.getId());
            paymentHistoryRepository.deleteAll(paymentHistories);
            
            // Hard delete the receipt
            receiptRepository.delete(receipt);
            
            log.warn("PERMANENTLY deleted receipt: {}", receipt.getReceiptNo());
            
        } catch (Exception e) {
            log.error("Error permanently deleting receipt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to permanently delete receipt: " + e.getMessage());
        }
    }
    @Transactional
    public ReceiptEntity restoreReceipt(Long id, Long userId) {
        try {
            ReceiptEntity receipt = receiptRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Receipt not found"));
            
            if (receipt.getDeletedAt() == null) {
                throw new RuntimeException("Receipt is not deleted");
            }
            
            log.info("Restoring receipt: {}", receipt.getReceiptNo());
            
            // Check if we can restore (validate invoice still exists if applicable)
            if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(receipt.getReceiptType()) &&
                receipt.getInvoiceId() != null) {
                
                InvoiceEntity invoice = invoiceRepository.findById(receipt.getInvoiceId())
                        .orElseThrow(() -> new RuntimeException(
                            "Cannot restore: Associated invoice no longer exists"
                        ));
                
                if (invoice.getDeletedAt() != null) {
                    throw new RuntimeException(
                        "Cannot restore: Associated invoice is deleted"
                    );
                }
            }
            
            // Restore the receipt
            receipt.setDeletedAt(null);
            receipt.setUpdatedAt(LocalDateTime.now());
            
            ReceiptEntity restored = receiptRepository.save(receipt);
            
            log.info("Successfully restored receipt: {}", restored.getReceiptNo());
            return restored;
            
        } catch (Exception e) {
            log.error("Error restoring receipt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to restore receipt: " + e.getMessage());
        }
    }
    @Transactional(readOnly = true)
    public List<ReceiptEntity> getDeletedReceipts(Long userId, String userRole) {
        try {
            boolean isAdmin = isAdmin(userRole);
            
            if (isAdmin) {
                return receiptRepository.findDeletedReceipts();
            } else {
                return receiptRepository.findDeletedReceiptsByUser(userId);
            }
            
        } catch (Exception e) {
            log.error("Error fetching deleted receipts: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch deleted receipts: " + e.getMessage());
        }
    }
    /**
     * Get receipt statistics — overload without userRole (kept for internal callers)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getReceiptSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy
    ) {
        return getReceiptSummary(groupId, subGroupId, projectId, createdBy, null);
    }

    /**
     * Get receipt statistics with project-access-aware scoping.
     * - Admin: sees all receipts in scope
     * - Non-admin: sees only receipts from projects they have access to
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getReceiptSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy,
            String userRole
    ) {
        return getReceiptSummary(groupId, subGroupId, projectId, createdBy, userRole, null, null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReceiptSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy,
            String userRole,
            String searchTerm,
            String receiptTypeFilter,
            String paymentMethodFilter
    ) {
        return getReceiptSummary(groupId, subGroupId, projectId, createdBy, userRole, searchTerm, receiptTypeFilter, paymentMethodFilter, null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReceiptSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy,
            String userRole,
            String searchTerm,
            String receiptTypeFilter,
            String paymentMethodFilter,
            String fromDate,
            String toDate
    ) {
        // Reuse findFiltered (which already has date support) to guarantee KPIs match table exactly
        String cleanGroup   = (groupId    != null && !groupId.trim().isEmpty())    ? groupId.trim()    : null;
        String cleanSub     = (subGroupId != null && !subGroupId.trim().isEmpty()) ? subGroupId.trim() : null;
        String cleanProject = (projectId  != null && !projectId.trim().isEmpty())  ? projectId.trim()  : null;
        String cleanSearch  = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;
        String cleanType    = (receiptTypeFilter != null && !receiptTypeFilter.trim().isEmpty() && !"all".equalsIgnoreCase(receiptTypeFilter.trim())) ? receiptTypeFilter.trim() : null;
        String cleanPayment = (paymentMethodFilter != null && !paymentMethodFilter.trim().isEmpty() && !"all".equalsIgnoreCase(paymentMethodFilter.trim())) ? paymentMethodFilter.trim() : null;

        LocalDateTime from = (fromDate != null && !fromDate.isBlank())
                ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to   = (toDate   != null && !toDate.isBlank())
                ? LocalDate.parse(toDate).atTime(23, 59, 59) : null;

        boolean isAdmin = isAdmin(userRole);
        Pageable all = Pageable.unpaged();

        List<ReceiptEntity> receipts;
        if (cleanProject != null) {
            receipts = receiptRepository.findFiltered(null, null, cleanProject, null, cleanType, cleanPayment, cleanSearch, from, to, all).getContent();
        } else if (isAdmin) {
            receipts = receiptRepository.findFiltered(cleanGroup, cleanSub, null, null, cleanType, cleanPayment, cleanSearch, from, to, all).getContent();
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(createdBy, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                receipts = receiptRepository.findFilteredAccessible(accessibleProjectIds, cleanGroup, cleanSub, cleanType, cleanPayment, cleanSearch, from, to, all).getContent();
            } else {
                receipts = receiptRepository.findFiltered(cleanGroup, cleanSub, null, createdBy, cleanType, cleanPayment, cleanSearch, from, to, all).getContent();
            }
        }

        long totalReceipts   = receipts.size();
        BigDecimal totalAmount     = receipts.stream().map(r -> r.getAmount()           != null ? r.getAmount()           : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal appliedAmount   = receipts.stream().map(r -> r.getAppliedAmount()    != null ? r.getAppliedAmount()    : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unappliedAmount = receipts.stream().map(r -> r.getUnappliedAmount()  != null ? r.getUnappliedAmount()  : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        long advanceReceipts = receipts.stream().filter(r -> ReceiptEntity.ReceiptType.ADVANCE.equalsIgnoreCase(r.getReceiptType())).count();
        long invoiceReceipts = receipts.stream().filter(r -> ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equalsIgnoreCase(r.getReceiptType())).count();

        Map<String, Object> result = new HashMap<>();
        result.put("totalReceipts",   totalReceipts);
        result.put("totalAmount",     totalAmount);
        result.put("appliedAmount",   appliedAmount);
        result.put("unappliedAmount", unappliedAmount);
        result.put("advanceReceipts", advanceReceipts);
        result.put("invoiceReceipts", invoiceReceipts);
        return result;
    }
    
    // Helper methods
    
    /**
     * Returns true when the user should see ALL data without project-access filtering.
     * Rules:
     *   1. ADMIN / SUPERADMIN always bypass.
     *   2. Any role starting with ACCOUNTS_ bypasses (ACCOUNTS_EXECUTIVE, ACCOUNTS_CFO, etc.).
     *   3. Any role whose level_order in role_hierarchy is <= 2 bypasses.
     */
    private boolean isAdmin(String userRole) {
        if (userRole == null) return false;
        String r = userRole.trim().toUpperCase();
        if (r.equals("ADMIN") || r.equals("SUPERADMIN")) return true;
        if (r.startsWith("ACCOUNTS_")) return true;
        return roleHierarchyRepo.findLevelOrderByRoleName(RoleNormalizer.normalize(userRole))
                .map(level -> level <= 2)
                .orElse(false);
    }
    
 // Replace the generateReceiptNumber method in ReceiptService.java

/**
 * Generate unique receipt number using MAX logic with retry
 * Format: RCP-YYYY-NNNN
 */
/**
 * Generate unique receipt number with database verification
 * Format: RCP-YYYY-NNNN
 */
/**
 * Generate unique receipt number
 * Format: RCP-YYYY-NNNN
 * 
 * Algorithm:
 * 1. Get last receipt number from database (e.g., RCP-2026-0016)
 * 2. Extract number part and add 1 (0016 + 1 = 0017)
 * 3. Check if new number exists
 * 4. If exists, increment and check again
 * 5. Return first available number
 */
private synchronized String generateReceiptNumber() {
    int currentYear = Year.now().getValue();
    String prefix = "RCP-" + currentYear + "-";
    
    int maxAttempts = 20;
    
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
        try {
            // Step 1: Get the last receipt number from database
            String lastReceiptNo = receiptRepository.findLastReceiptNoByPrefix(prefix + "%");
            
            int nextNumber = 1;
            
            if (lastReceiptNo != null && !lastReceiptNo.isEmpty()) {
                log.info("Found last receipt: {}", lastReceiptNo);
                
                // Step 2: Extract the numeric part (e.g., "RCP-2026-0016" -> "0016")
                String numericPart = lastReceiptNo.replace(prefix, "");
                
                try {
                    int lastNumber = Integer.parseInt(numericPart);
                    nextNumber = lastNumber + 1;
                    log.info("Last number was {}, next will be {}", lastNumber, nextNumber);
                } catch (NumberFormatException e) {
                    log.error("Could not parse number from: {}. Starting from 1", lastReceiptNo);
                    nextNumber = 1;
                }
            } else {
                log.info("No receipts found for year {}. Starting from 1", currentYear);
            }
            
            // Step 3: Generate the new receipt number
            String receiptNo = prefix + String.format("%04d", nextNumber);
            log.info("Attempting to use receipt number: {}", receiptNo);
            
            // Step 4: Check if this number already exists
            Optional<ReceiptEntity> existing = receiptRepository.findByReceiptNoIncludingDeleted(receiptNo);
            
            if (existing.isPresent()) {
                log.warn("Receipt {} already exists! Trying next number...", receiptNo);
                
                // Try incrementing
                nextNumber++;
                receiptNo = prefix + String.format("%04d", nextNumber);
                
                existing = receiptRepository.findByReceiptNoIncludingDeleted(receiptNo);
                
                if (existing.isEmpty()) {
                    log.info("Using incremented receipt number: {}", receiptNo);
                    return receiptNo;
                }
                
                // If still exists, continue loop to query database again
                Thread.sleep(50);
                continue;
            }
            
            // Step 5: Number is available, return it
            log.info("Receipt number {} is available and confirmed unique", receiptNo);
            return receiptNo;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted while generating receipt number");
        } catch (Exception e) {
            log.error("Error on attempt {} generating receipt number: {}", attempt + 1, e.getMessage());
            
            if (attempt >= maxAttempts - 1) {
                throw new RuntimeException("Failed to generate receipt number after " + maxAttempts + " attempts: " + e.getMessage());
            }
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    throw new RuntimeException("Failed to generate unique receipt number after " + maxAttempts + " attempts");
}
@Transactional(readOnly = true)
public List<Map<String, Object>> getAllocationDetails(Long receiptId) {
    try {
        List<AdvanceAllocationEntity> allocations = allocationRepository
                .findByReceiptIdOrderByAllocationDateDesc(receiptId);
        
        List<Map<String, Object>> details = new ArrayList<>();
        
        for (AdvanceAllocationEntity allocation : allocations) {
            InvoiceEntity invoice = invoiceRepository.findById(allocation.getInvoiceId())
                    .orElse(null);
            
            if (invoice != null) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("allocationId", allocation.getId());
                detail.put("invoiceId", invoice.getId());
                detail.put("invoiceNo", invoice.getInvoiceNo());
                detail.put("allocatedAmount", allocation.getAllocatedAmount());
                detail.put("allocationDate", allocation.getAllocationDate());
                detail.put("invoiceTotal", invoice.getTotalAmount());
                detail.put("invoiceBalance", invoice.getBalanceAmount());
                detail.put("invoiceStatus", invoice.getStatus());
                
                details.add(detail);
            }
        }
        
        return details;
        
    } catch (Exception e) {
        log.error("Error getting allocation details for receipt: {}", receiptId, e);
        throw new RuntimeException("Failed to get allocation details: " + e.getMessage());
    }
}
@Transactional
public ReceiptEntity editAllocation(Long receiptId, Long oldInvoiceId, Long newInvoiceId, 
                                    BigDecimal newAmount, Long userId) {
    try {
        ReceiptEntity receipt = getReceiptById(receiptId);
        
        log.info("Editing allocation for receipt {} - moving from invoice {} to invoice {}", 
                receipt.getReceiptNo(), oldInvoiceId, newInvoiceId);
        
        // Validate receipt type
        if (!ReceiptEntity.ReceiptType.ADVANCE.equals(receipt.getReceiptType())) {
            throw new RuntimeException("Can only edit allocations for advance receipts");
        }
        
        // Find the existing allocation
        AdvanceAllocationEntity existingAllocation = allocationRepository
                .findByReceiptIdAndInvoiceId(receiptId, oldInvoiceId)
                .orElseThrow(() -> new RuntimeException("Allocation not found for this invoice"));
        
        BigDecimal oldAmount = existingAllocation.getAllocatedAmount();
        
        log.info("Found existing allocation: {} to invoice {}", oldAmount, oldInvoiceId);
        
        // Step 1: Reverse the old allocation from Invoice A
        InvoiceEntity oldInvoice = invoiceRepository.findById(oldInvoiceId)
                .orElseThrow(() -> new RuntimeException("Old invoice not found"));
        
        log.info("Reversing {} from invoice {}", oldAmount, oldInvoice.getInvoiceNo());
        
        // Subtract the old amount from paid_amount
        BigDecimal oldInvoicePaidAmount = oldInvoice.getPaidAmount() != null ? 
                oldInvoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newOldInvoicePaidAmount = oldInvoicePaidAmount.subtract(oldAmount);
        
        if (newOldInvoicePaidAmount.compareTo(BigDecimal.ZERO) < 0) {
            newOldInvoicePaidAmount = BigDecimal.ZERO;
        }
        
        oldInvoice.setPaidAmount(newOldInvoicePaidAmount);
        
        // Update old invoice status
        if (newOldInvoicePaidAmount.compareTo(BigDecimal.ZERO) == 0) {
            oldInvoice.setStatus(InvoiceEntity.Status.SENT);
        } else if (newOldInvoicePaidAmount.compareTo(oldInvoice.getTotalAmount()) < 0) {
            oldInvoice.setStatus(InvoiceEntity.Status.PARTIALLY_PAID);
        } else {
            oldInvoice.setStatus(InvoiceEntity.Status.PAID);
        }
        
        oldInvoice.setUpdatedAt(LocalDateTime.now());
        invoiceRepository.save(oldInvoice);
        
        log.info("Updated old invoice {} - new paid amount: {}, status: {}", 
                oldInvoice.getInvoiceNo(), newOldInvoicePaidAmount, oldInvoice.getStatus());
        
        // Step 2: Delete the old allocation record
        allocationRepository.delete(existingAllocation);
        
        // Update receipt applied amount
        BigDecimal receiptAppliedAmount = receipt.getAppliedAmount().subtract(oldAmount);
        receipt.setAppliedAmount(receiptAppliedAmount);
        
        log.info("Deleted old allocation and updated receipt applied amount to: {}", receiptAppliedAmount);
        
        // Step 3: Apply to new Invoice B (if provided)
        if (newInvoiceId != null && newAmount.compareTo(BigDecimal.ZERO) > 0) {
            InvoiceEntity newInvoice = invoiceRepository.findById(newInvoiceId)
                    .orElseThrow(() -> new RuntimeException("New invoice not found"));
            
            log.info("Applying {} to new invoice {}", newAmount, newInvoice.getInvoiceNo());
            
            // Validate amount doesn't exceed invoice balance
            if (newAmount.compareTo(newInvoice.getBalanceAmount()) > 0) {
                throw new RuntimeException(
                    "Amount " + newAmount + " exceeds invoice balance " + newInvoice.getBalanceAmount()
                );
            }
            
            // Validate amount doesn't exceed available advance
            BigDecimal availableAmount = receipt.getAmount().subtract(receiptAppliedAmount);
            if (newAmount.compareTo(availableAmount) > 0) {
                throw new RuntimeException(
                    "Amount " + newAmount + " exceeds available advance " + availableAmount
                );
            }
            
            // Create new allocation
            AdvanceAllocationEntity newAllocation = AdvanceAllocationEntity.builder()
                    .receipt(receipt)
                    .invoiceId(newInvoiceId)
                    .allocatedAmount(newAmount)
                    .allocationDate(LocalDateTime.now())
                    .allocatedBy(userId)
                    .build();
            
            allocationRepository.save(newAllocation);
            
            log.info("Created new allocation: {} to invoice {}", newAmount, newInvoice.getInvoiceNo());
            
            // Update new invoice
            BigDecimal newInvoicePaidAmount = newInvoice.getPaidAmount() != null ? 
                    newInvoice.getPaidAmount() : BigDecimal.ZERO;
            newInvoicePaidAmount = newInvoicePaidAmount.add(newAmount);
            
            newInvoice.setPaidAmount(newInvoicePaidAmount);
            
            // Update status
            if (newInvoicePaidAmount.compareTo(newInvoice.getTotalAmount()) >= 0) {
                newInvoice.setStatus(InvoiceEntity.Status.PAID);
            } else if (newInvoicePaidAmount.compareTo(BigDecimal.ZERO) > 0) {
                newInvoice.setStatus(InvoiceEntity.Status.PARTIALLY_PAID);
            }
            
            newInvoice.setUpdatedAt(LocalDateTime.now());
            invoiceRepository.save(newInvoice);
            
            log.info("Updated new invoice {} - new paid amount: {}, status: {}", 
                    newInvoice.getInvoiceNo(), newInvoicePaidAmount, newInvoice.getStatus());
            
            // Update receipt applied amount
            receiptAppliedAmount = receiptAppliedAmount.add(newAmount);
            receipt.setAppliedAmount(receiptAppliedAmount);
            
            // ✅ FIX: Create payment history for new invoice - Use invoice object, not ID
            PaymentHistoryEntity paymentHistory = PaymentHistoryEntity.builder()
                    .invoice(newInvoice)  // ✅ Set the relationship object
                    .receiptId(receipt.getId())
                    .amount(newAmount)
                    .paymentDate(LocalDateTime.now())
                    .paymentMethod(receipt.getPaymentMethod())
                    .transactionReference(receipt.getTransactionReference())
                    .notes("Advance allocation moved from invoice " + oldInvoice.getInvoiceNo() + 
                          " (was " + oldAmount + ") to " + newInvoice.getInvoiceNo() + 
                          " (new amount " + newAmount + ")")
                    .recordedBy(userId)
                    .build();

            paymentHistoryRepository.save(paymentHistory);
        }
        
        // Mark old payment history
        List<PaymentHistoryEntity> oldPayments = paymentHistoryRepository
                .findByInvoiceIdAndReceiptId(oldInvoiceId, receipt.getId());
        
        for (PaymentHistoryEntity payment : oldPayments) {
            payment.setNotes((payment.getNotes() != null ? payment.getNotes() + " | " : "") + 
                           "MOVED to invoice " + newInvoiceId + " - allocation edited");
            paymentHistoryRepository.save(payment);
        }
        
        receipt.setUpdatedAt(LocalDateTime.now());
        ReceiptEntity updated = receiptRepository.save(receipt);
        
        log.info("Successfully edited allocation for receipt {}", receipt.getReceiptNo());
        
        return updated;
        
    } catch (Exception e) {
        log.error("Error editing allocation: {}", e.getMessage(), e);
        throw new RuntimeException("Failed to edit allocation: " + e.getMessage());
    }
}
@Transactional
public ReceiptEntity removeSpecificAllocation(Long receiptId, Long invoiceId, Long userId) {
    try {
        ReceiptEntity receipt = getReceiptById(receiptId);
        
        log.info("Removing allocation from receipt {} for invoice {}", 
                receipt.getReceiptNo(), invoiceId);
        
        // Find the allocation
        AdvanceAllocationEntity allocation = allocationRepository
                .findByReceiptIdAndInvoiceId(receiptId, invoiceId)
                .orElseThrow(() -> new RuntimeException("Allocation not found"));
        
        BigDecimal amount = allocation.getAllocatedAmount();
        
        // Reverse the payment from invoice
        reverseInvoicePayment(invoiceId, amount, receiptId);
        
        // Delete allocation
        allocationRepository.delete(allocation);
        
        // Update receipt applied amount
        BigDecimal newAppliedAmount = receipt.getAppliedAmount().subtract(amount);
        receipt.setAppliedAmount(newAppliedAmount);
        receipt.setUpdatedAt(LocalDateTime.now());
        
        ReceiptEntity updated = receiptRepository.save(receipt);
        
        log.info("Removed allocation of {} from invoice {}", amount, invoiceId);
        
        return updated;
        
    } catch (Exception e) {
        log.error("Error removing allocation: {}", e.getMessage(), e);
        throw new RuntimeException("Failed to remove allocation: " + e.getMessage());
    }
}
    @Transactional
    public ReceiptEntity updateReceipt(Long receiptId, ReceiptEntity updatedReceipt, Long userId) {
    try {
        ReceiptEntity existing = getReceiptById(receiptId);
        
        log.info("Updating receipt: {}", existing.getReceiptNo());
        
        // Store old values for reversal if needed
        Long oldInvoiceId = existing.getInvoiceId();
        BigDecimal oldAmount = existing.getAmount();
        String oldReceiptType = existing.getReceiptType();
        
        // DECLARE THESE VARIABLES OUTSIDE THE IF BLOCK
        boolean invoiceChanged = false;
        boolean amountChanged = !oldAmount.equals(updatedReceipt.getAmount());
        
        // Check if receipt has been allocated
        if (ReceiptEntity.ReceiptType.ADVANCE.equals(existing.getReceiptType()) &&
            existing.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            
            // If advance has allocations, we need to handle carefully
            if (amountChanged) {
                // Check if new amount is less than applied amount
                if (updatedReceipt.getAmount().compareTo(existing.getAppliedAmount()) < 0) {
                    throw new RuntimeException(
                        "Cannot reduce receipt amount below allocated amount. " +
                        "Current allocated: " + existing.getAppliedAmount() + 
                        ". Please deallocate first."
                    );
                }
            }
        }
        
        // If changing from invoice payment type or changing invoice
        if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(oldReceiptType) && oldInvoiceId != null) {
            invoiceChanged = updatedReceipt.getInvoiceId() != null && 
                           !oldInvoiceId.equals(updatedReceipt.getInvoiceId());
            
            if (invoiceChanged || amountChanged) {
                // Reverse old payment
                reverseInvoicePayment(oldInvoiceId, oldAmount, receiptId);
            }
        }
        
        // ── Capture old project before any changes ──────────────────────────────
        String oldProjectIdForReceipt = existing.getProjectId();
        String newProjectIdForReceipt = updatedReceipt.getProjectId() != null
                ? (updatedReceipt.getProjectId().isBlank() ? null : updatedReceipt.getProjectId())
                : existing.getProjectId();
        boolean receiptProjectChanged = ReceiptEntity.ReceiptType.ADVANCE.equals(existing.getReceiptType())
                && newProjectIdForReceipt != null
                && !newProjectIdForReceipt.equals(oldProjectIdForReceipt);

        // ── ADVANCE with allocations: if project changes, reverse all invoice allocations first ──
        if (receiptProjectChanged && existing.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            List<com.istlgroup.istl_group_crm_backend.entity.AdvanceAllocationEntity> allocs =
                    allocationRepository.findAllocationsByReceipt(existing.getId());
            for (com.istlgroup.istl_group_crm_backend.entity.AdvanceAllocationEntity a : allocs) {
                reverseInvoicePayment(a.getInvoiceId(), a.getAllocatedAmount(), existing.getId());
                log.info("Reversed allocation of {} from invoice id {} due to project reassignment of receipt {}",
                         a.getAllocatedAmount(), a.getInvoiceId(), existing.getReceiptNo());
            }
            allocationRepository.deleteAll(allocs);
            existing.setAppliedAmount(BigDecimal.ZERO);
            log.info("Cleared all allocations for receipt [{}] before project reassignment {} → {}",
                     existing.getReceiptNo(), oldProjectIdForReceipt, newProjectIdForReceipt);
        }

        // Update basic fields
        existing.setReceiptDate(updatedReceipt.getReceiptDate());
        existing.setAmount(updatedReceipt.getAmount());
        existing.setPaymentMethod(updatedReceipt.getPaymentMethod());
        existing.setTransactionReference(updatedReceipt.getTransactionReference());
        existing.setNotes(updatedReceipt.getNotes());
        existing.setCompany(updatedReceipt.getCompany());

        // ── Update project assignment only for ADVANCE type — INVOICE_PAYMENT project is locked ──
        if (ReceiptEntity.ReceiptType.ADVANCE.equals(existing.getReceiptType())) {
            if (updatedReceipt.getProjectId() != null) {
                existing.setProjectId(updatedReceipt.getProjectId().isBlank() ? null : updatedReceipt.getProjectId());
            }
            if (updatedReceipt.getGroupId() != null) {
                existing.setGroupId(updatedReceipt.getGroupId().isBlank() ? null : updatedReceipt.getGroupId());
            }
            if (updatedReceipt.getSubGroupId() != null) {
                existing.setSubGroupId(updatedReceipt.getSubGroupId().isBlank() ? null : updatedReceipt.getSubGroupId());
            }
        }
        
        // Handle receipt type and invoice changes
        if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(updatedReceipt.getReceiptType())) {
            if (updatedReceipt.getInvoiceId() == null) {
                throw new RuntimeException("Invoice ID is required for invoice payments");
            }
            
            // Validate payment amount
            InvoiceEntity invoice = invoiceRepository.findById(updatedReceipt.getInvoiceId())
                    .orElseThrow(() -> new RuntimeException("Invoice not found"));
            
            // If this is a new invoice or invoice changed, apply payment
            if (invoiceChanged || !updatedReceipt.getInvoiceId().equals(oldInvoiceId)) {
                if (updatedReceipt.getAmount().compareTo(invoice.getBalanceAmount()) > 0) {
                    throw new RuntimeException("Payment amount exceeds invoice balance");
                }
                
                existing.setInvoiceId(updatedReceipt.getInvoiceId());
                existing.setAppliedAmount(updatedReceipt.getAmount());
                
                // Apply new payment
                updateInvoicePayment(updatedReceipt.getInvoiceId(), updatedReceipt.getAmount(), 
                                   receiptId, userId, updatedReceipt.getPaymentMethod(),
                                   updatedReceipt.getTransactionReference(), 
                                   "Updated receipt: " + existing.getReceiptNo(), true);
            } else if (amountChanged && updatedReceipt.getInvoiceId().equals(oldInvoiceId)) {
                // Same invoice, different amount - update the difference
                BigDecimal amountDifference = updatedReceipt.getAmount().subtract(oldAmount);
                
                // Recalculate the invoice balance to check validity
                BigDecimal currentInvoiceBalance = invoice.getBalanceAmount();
                
                if (amountDifference.compareTo(BigDecimal.ZERO) > 0) {
                    // Increased amount - validate against current balance
                    if (amountDifference.compareTo(currentInvoiceBalance) > 0) {
                        throw new RuntimeException(
                            "Payment increase of " + amountDifference + 
                            " exceeds invoice balance of " + currentInvoiceBalance
                        );
                    }
                }
                
                existing.setAppliedAmount(updatedReceipt.getAmount());
                
                // Update invoice payment with difference
                updateInvoicePayment(existing.getInvoiceId(), amountDifference, 
                                   receiptId, userId, updatedReceipt.getPaymentMethod(),
                                   updatedReceipt.getTransactionReference(),
                                   "Updated receipt amount: " + existing.getReceiptNo(), true);
            }
        } else {
            // It's an advance
            existing.setReceiptType(ReceiptEntity.ReceiptType.ADVANCE);
            existing.setInvoiceId(null);
            
            // If changing from INVOICE_PAYMENT to ADVANCE, reset applied amount
            if (ReceiptEntity.ReceiptType.INVOICE_PAYMENT.equals(oldReceiptType)) {
                existing.setAppliedAmount(BigDecimal.ZERO);
            }
        }
        
        existing.setUpdatedAt(LocalDateTime.now());
        ReceiptEntity saved = receiptRepository.save(existing);

        // FIX: Sync project paid_invoice_value after every receipt update.
        // Since paid_invoice_value = SUM(receipts.amount) for the project, a single
        // sync call correctly reflects the new amount — whether the receipt amount
        // changed, the project changed, or the receipt type changed.
        // This also retroactively fixes projects that were never updated before this
        // fix was applied, because the SUM query covers all existing receipts.
        String projectIdToSync = saved.getProjectId() != null ? saved.getProjectId()
                : (existing.getProjectId() != null ? existing.getProjectId() : null);
        if (projectIdToSync != null && !projectIdToSync.isBlank()) {
            invoiceService.syncProjectInvoiceStats(projectIdToSync);
            log.info("Synced project [{}] paid_invoice_value after receipt [{}] update",
                     projectIdToSync, saved.getReceiptNo());
        }
        // If project changed, also sync the old project so it no longer includes this receipt
        if (oldProjectIdForReceipt != null && !oldProjectIdForReceipt.isBlank()
                && !oldProjectIdForReceipt.equals(projectIdToSync)) {
            invoiceService.syncProjectInvoiceStats(oldProjectIdForReceipt);
            log.info("Synced old project [{}] paid_invoice_value after receipt [{}] moved",
                     oldProjectIdForReceipt, saved.getReceiptNo());
        }

        log.info("Receipt updated successfully: {}", saved.getReceiptNo());
        return saved;
        
    } catch (Exception e) {
        log.error("Error updating receipt: {}", e.getMessage(), e);
        throw new RuntimeException("Failed to update receipt: " + e.getMessage());
    }
}
    private void reverseInvoicePayment(Long invoiceId, BigDecimal amount, Long receiptId) {
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        
        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaidAmount = currentPaid.subtract(amount);
        
        if (newPaidAmount.compareTo(BigDecimal.ZERO) < 0) {
            newPaidAmount = BigDecimal.ZERO;
        }
        
        invoice.setPaidAmount(newPaidAmount);
        
        // Update status
        if (newPaidAmount.compareTo(BigDecimal.ZERO) == 0) {
            invoice.setStatus(InvoiceEntity.Status.SENT);
        } else if (newPaidAmount.compareTo(invoice.getTotalAmount()) < 0) {
            invoice.setStatus(InvoiceEntity.Status.PARTIALLY_PAID);
        }
        
        invoice.setUpdatedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);
        
        // Mark payment history as reversed
        List<PaymentHistoryEntity> payments = paymentHistoryRepository
                .findByInvoiceIdAndReceiptId(invoiceId, receiptId);
        
        for (PaymentHistoryEntity payment : payments) {
            payment.setNotes((payment.getNotes() != null ? payment.getNotes() + " | " : "") + 
                            "REVERSED - Receipt updated");
            paymentHistoryRepository.save(payment);
        }

        // ── Sync project invoice stats (replaces dropped DB trigger) ────────
        if (invoice.getProjectId() != null && !invoice.getProjectId().isBlank()) {
            invoiceService.syncProjectInvoiceStats(invoice.getProjectId());
        }
        
        log.info("Reversed payment of {} from invoice {}", amount, invoice.getInvoiceNo());
    }
    @Transactional
    public ReceiptEntity updateAdvanceAllocation(Long receiptId, Long oldInvoiceId, Long newInvoiceId, 
                                                BigDecimal amount, Long userId) {
        try {
            ReceiptEntity receipt = getReceiptById(receiptId);
            
            // Validate it's an advance
            if (!ReceiptEntity.ReceiptType.ADVANCE.equals(receipt.getReceiptType())) {
                throw new RuntimeException("Only advance receipts can have allocations updated");
            }
            
            // Find existing allocation
            AdvanceAllocationEntity existingAllocation = allocationRepository
                    .findByReceiptIdAndInvoiceId(receiptId, oldInvoiceId)
                    .orElseThrow(() -> new RuntimeException("Allocation not found"));
            
            BigDecimal oldAllocationAmount = existingAllocation.getAllocatedAmount();
            
            // Reverse old allocation
            reverseInvoicePayment(oldInvoiceId, oldAllocationAmount, receiptId);
            
            // Delete old allocation record
            allocationRepository.delete(existingAllocation);
            
            // Update receipt applied amount
            BigDecimal newAppliedAmount = receipt.getAppliedAmount().subtract(oldAllocationAmount);
            receipt.setAppliedAmount(newAppliedAmount);
            
            // Create new allocation if newInvoiceId is provided
            if (newInvoiceId != null) {
                InvoiceEntity newInvoice = invoiceRepository.findById(newInvoiceId)
                        .orElseThrow(() -> new RuntimeException("New invoice not found"));
                
                // Validate amount
                if (amount.compareTo(newInvoice.getBalanceAmount()) > 0) {
                    throw new RuntimeException("Allocation amount exceeds invoice balance");
                }
                
                if (amount.compareTo(receipt.getUnappliedAmount().add(oldAllocationAmount)) > 0) {
                    throw new RuntimeException("Allocation amount exceeds available advance");
                }
                
                // Create new allocation
                AdvanceAllocationEntity newAllocation = AdvanceAllocationEntity.builder()
                        .receipt(receipt)
                        .invoiceId(newInvoiceId)
                        .allocatedAmount(amount)
                        .allocationDate(LocalDateTime.now())
                        .allocatedBy(userId)
                        .build();
                
                allocationRepository.save(newAllocation);
                
                // Apply to new invoice
                updateInvoicePayment(newInvoiceId, amount, receiptId, userId, 
                                   receipt.getPaymentMethod(), receipt.getTransactionReference(),
                                   "Advance allocation moved from invoice " + oldInvoiceId, false);
                
                // Update receipt applied amount
                newAppliedAmount = newAppliedAmount.add(amount);
                receipt.setAppliedAmount(newAppliedAmount);
            }
            
            receipt.setUpdatedAt(LocalDateTime.now());
            ReceiptEntity updated = receiptRepository.save(receipt);
            
            log.info("Updated advance allocation for receipt {}", receipt.getReceiptNo());
            return updated;
            
        } catch (Exception e) {
            log.error("Error updating advance allocation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update advance allocation: " + e.getMessage());
        }
    }
    private void updateInvoicePayment(Long invoiceId, BigDecimal amount, Long receiptId, 
                                     Long userId, String paymentMethod, String transactionRef,
                                     String notes, boolean updateProjectStats) {
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        
        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaidAmount = currentPaid.add(amount);
        
        invoice.setPaidAmount(newPaidAmount);
        
        // Update status
        if (newPaidAmount.compareTo(invoice.getTotalAmount()) >= 0) {
            invoice.setStatus(InvoiceEntity.Status.PAID);
        } else if (newPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            invoice.setStatus(InvoiceEntity.Status.PARTIALLY_PAID);
        }
        
        invoice.setUpdatedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);
        
        // Create payment history
        PaymentHistoryEntity paymentHistory = PaymentHistoryEntity.builder()
                .invoice(invoice)
                .receiptId(receiptId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .transactionReference(transactionRef)
                .notes(notes)
                .recordedBy(userId) 
                .paymentDate(LocalDateTime.now())
                .build();
        
        paymentHistoryRepository.save(paymentHistory);

        // Only sync project stats for DIRECT receipt payments (INVOICE_PAYMENT type).
        // Advance allocations must NOT trigger this — the advance amount was already
        // counted in paidInvoiceValue when the advance receipt was originally recorded.
        // Double-calling this for allocations would inflate project.paid_invoice_value.
        if (updateProjectStats && invoice.getProjectId() != null && !invoice.getProjectId().isBlank()) {
            invoiceService.syncProjectInvoiceStats(invoice.getProjectId());
        }
        
        log.info("Updated invoice {} with payment of {}", invoice.getInvoiceNo(), amount);
    }
 // Add to ReceiptService.java

    /**
     * Get unapplied advances for a customer (for allocating to invoices)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUnappliedAdvancesWithDetails(Long customerId) {
        List<ReceiptEntity> advances = receiptRepository.findUnappliedAdvancesByCustomer(customerId);
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (ReceiptEntity advance : advances) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", advance.getId());
            item.put("receiptNo", advance.getReceiptNo());
            item.put("receiptDate", advance.getReceiptDate());
            item.put("amount", advance.getAmount());
            item.put("appliedAmount", advance.getAppliedAmount());
            item.put("unappliedAmount", advance.getUnappliedAmount());
            item.put("paymentMethod", advance.getPaymentMethod());
            
            result.add(item);
        }
        
        return result;
    }
 // Add to ReceiptService.java

    /**
     * Remove advance allocation
     */
    @Transactional
    public void removeAdvanceAllocation(Long receiptId, Long allocationId, Long userId) {
        try {
            AdvanceAllocationEntity allocation = allocationRepository.findById(allocationId)
                    .orElseThrow(() -> new RuntimeException("Allocation not found"));
            
            ReceiptEntity receipt = allocation.getReceipt();
            
            if (!receipt.getId().equals(receiptId)) {
                throw new RuntimeException("Allocation does not belong to this receipt");
            }
            
            // Reverse the payment
            reverseInvoicePayment(allocation.getInvoiceId(), allocation.getAllocatedAmount(), receiptId);
            
            // Update receipt applied amount
            BigDecimal newAppliedAmount = receipt.getAppliedAmount().subtract(allocation.getAllocatedAmount());
            receipt.setAppliedAmount(newAppliedAmount);
            receipt.setUpdatedAt(LocalDateTime.now());
            receiptRepository.save(receipt);
            
            // Delete allocation
            allocationRepository.delete(allocation);
            
            log.info("Removed allocation {} from receipt {}", allocationId, receipt.getReceiptNo());
            
        } catch (Exception e) {
            log.error("Error removing allocation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to remove allocation: " + e.getMessage());
        }
    }
}