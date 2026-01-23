// InvoiceService.java
package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceRepository;
import com.istlgroup.istl_group_crm_backend.repo.PaymentHistoryRepository;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {
    
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final CustomersRepo customerRepository;
    private final InvoicePdfService pdfService;
    private final PaymentHistoryRepository paymentHistoryRepository;

    /**
     * Get invoices with role-based and project-based filtering
     */
    @Transactional(readOnly = true)
    public Page<InvoiceEntity> getInvoices(
            String groupId,
            String subGroupId,
            String projectId,
            String status,
            String searchTerm,
            Long userId,
            String userRole,
            int page,
            int size,
            String sortBy,
            String sortDirection
    ) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy)
        );
        
        boolean isAdmin = isAdmin(userRole);
        
        // Search takes priority
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            if (isAdmin) {
                return invoiceRepository.searchInvoices(searchTerm, pageable);
            } else {
                return invoiceRepository.searchInvoicesWithUserAccess(searchTerm, userId, pageable);
            }
        }
        
        // Project-based filtering
        if (isAdmin) {
            if (projectId != null && !projectId.isEmpty()) {
                return invoiceRepository.findByProjectId(projectId, pageable);
            }
            if (subGroupId != null && !subGroupId.isEmpty()) {
                return invoiceRepository.findByGroupAndSubGroup(groupId, subGroupId, pageable);
            }
            if (groupId != null && !groupId.isEmpty()) {
                return invoiceRepository.findByGroupId(groupId, pageable);
            }
            return invoiceRepository.findAllActive(pageable);
        } else {
            if (projectId != null && !projectId.isEmpty()) {
                return invoiceRepository.findByProjectIdAndUserAccess(projectId, userId, pageable);
            }
            if (subGroupId != null && !subGroupId.isEmpty()) {
                return invoiceRepository.findByGroupSubGroupAndUserAccess(groupId, subGroupId, userId, pageable);
            }
            if (groupId != null && !groupId.isEmpty()) {
                return invoiceRepository.findByGroupIdAndUserAccess(groupId, userId, pageable);
            }
            return invoiceRepository.findByUserAccess(userId, pageable);
        }
    }
    
    /**
     * Get invoice by ID
     */
    @Transactional(readOnly = true)
    public InvoiceEntity getInvoiceById(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found with id: " + id));
    }
    
    /**
     * Get invoice by ID with items eagerly loaded
     * This prevents lazy loading issues in JSON serialization
     */
    @Transactional(readOnly = true)
    public InvoiceEntity getInvoiceByIdWithItems(Long id) {
        InvoiceEntity invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found with id: " + id));
        
        // Force load items to avoid lazy loading
        if (invoice.getItems() != null) {
            invoice.getItems().size(); // Trigger lazy loading
        }
        
        return invoice;
    }
    
    /**
     * Get customer details by project ID
     */
    @Transactional(readOnly = true)
    public CustomersEntity getCustomerByProjectId(String projectId) {
        return customerRepository.findByProjectId(projectId)
                .orElseThrow(() -> new RuntimeException("Customer not found for project: " + projectId));
    }
    
    /**
     * Create new invoice
     */
    @Transactional
    public InvoiceEntity createInvoice(InvoiceEntity invoice, Long userId) {
        try {
            log.info("Creating invoice for user: {}", userId);
            
            // Generate invoice number
            invoice.setInvoiceNo(generateInvoiceNumber());
            
            // Set metadata
            invoice.setCreatedBy(userId);
            invoice.setCreatedAt(LocalDateTime.now());
            invoice.setUpdatedAt(LocalDateTime.now());
            
            // Set default status
            if (invoice.getStatus() == null || invoice.getStatus().isEmpty()) {
                invoice.setStatus(InvoiceEntity.Status.DRAFT);
            }
            
            // Set paid amount default
            if (invoice.getPaidAmount() == null) {
                invoice.setPaidAmount(BigDecimal.ZERO);
            }
            
            // Set invoice date to today if not provided
            if (invoice.getInvoiceDate() == null) {
                invoice.setInvoiceDate(LocalDate.now());
            }
            
            // Extract items
            List<InvoiceItemEntity> itemsToSave = new ArrayList<>();
            if (invoice.getItems() != null && !invoice.getItems().isEmpty()) {
                itemsToSave.addAll(invoice.getItems());
            }
            
            // Clear items to prevent cascade issues
            invoice.setItems(new ArrayList<>());
            
            // Calculate total
            BigDecimal totalAmount = calculateTotalAmount(itemsToSave);
            invoice.setTotalAmount(totalAmount);
            
            // Save invoice
            InvoiceEntity savedInvoice = invoiceRepository.save(invoice);
            log.info("Invoice saved with ID: {}", savedInvoice.getId());
            
            // Save items
            if (!itemsToSave.isEmpty()) {
                for (InvoiceItemEntity item : itemsToSave) {
                    item.setInvoice(savedInvoice);
                    if (item.getQuantity() == null) item.setQuantity(BigDecimal.ONE);
                    if (item.getUnitPrice() == null) item.setUnitPrice(BigDecimal.ZERO);
                    if (item.getTaxPercent() == null) item.setTaxPercent(BigDecimal.valueOf(18));
                }
                
                List<InvoiceItemEntity> savedItems = invoiceItemRepository.saveAll(itemsToSave);
                savedInvoice.setItems(savedItems);
            }
            
            log.info("Invoice created successfully: {}", savedInvoice.getInvoiceNo());
            return savedInvoice;
            
        } catch (Exception e) {
            log.error("Error creating invoice: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create invoice: " + e.getMessage());
        }
    }
    
    /**
     * Update invoice
     */
    @Transactional
    public InvoiceEntity updateInvoice(Long id, InvoiceEntity updatedInvoice) {
        InvoiceEntity existing = getInvoiceById(id);
        
        // Update fields
        existing.setCustomerId(updatedInvoice.getCustomerId());
        existing.setProjectId(updatedInvoice.getProjectId());
        existing.setGroupId(updatedInvoice.getGroupId());
        existing.setSubGroupId(updatedInvoice.getSubGroupId());
        existing.setInvoiceDate(updatedInvoice.getInvoiceDate());
        existing.setDueDate(updatedInvoice.getDueDate());
        existing.setStatus(updatedInvoice.getStatus());
        
        // Update items if provided
        if (updatedInvoice.getItems() != null) {
            invoiceItemRepository.deleteByInvoiceId(id);
            
            for (InvoiceItemEntity item : updatedInvoice.getItems()) {
                item.setInvoice(existing);
                if (item.getQuantity() == null) item.setQuantity(BigDecimal.ONE);
                if (item.getUnitPrice() == null) item.setUnitPrice(BigDecimal.ZERO);
                if (item.getTaxPercent() == null) item.setTaxPercent(BigDecimal.valueOf(18));
            }
            invoiceItemRepository.saveAll(updatedInvoice.getItems());
            
            // Recalculate total
            BigDecimal totalAmount = calculateTotalAmount(updatedInvoice.getItems());
            existing.setTotalAmount(totalAmount);
        }
        
        existing.setUpdatedAt(LocalDateTime.now());
        return invoiceRepository.save(existing);
    }
    
    /**
     * Update invoice status
     */
    @Transactional
    public InvoiceEntity updateStatus(Long id, String newStatus) {
        InvoiceEntity invoice = getInvoiceById(id);
        invoice.setStatus(newStatus);
        invoice.setUpdatedAt(LocalDateTime.now());
        
        log.info("Updated invoice {} status to: {}", invoice.getInvoiceNo(), newStatus);
        return invoiceRepository.save(invoice);
    }
    
    /**
     * Record payment
     */
    @Transactional
    public InvoiceEntity recordPayment(Long id, BigDecimal paymentAmount) {
        InvoiceEntity invoice = getInvoiceById(id);
        
        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaidAmount = currentPaid.add(paymentAmount);
        
        invoice.setPaidAmount(newPaidAmount);
        
        // Update status based on payment
        BigDecimal totalAmount = invoice.getTotalAmount();
        if (newPaidAmount.compareTo(totalAmount) >= 0) {
            invoice.setStatus(InvoiceEntity.Status.PAID);
        } else if (newPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            invoice.setStatus(InvoiceEntity.Status.PARTIALLY_PAID);
        }
        
        invoice.setUpdatedAt(LocalDateTime.now());
        
        log.info("Recorded payment of {} for invoice {}", paymentAmount, invoice.getInvoiceNo());
        return invoiceRepository.save(invoice);
    }
    
    /**
     * Soft delete invoice
     */
    @Transactional
    public void deleteInvoice(Long id) {
        InvoiceEntity invoice = getInvoiceById(id);
        invoice.setDeletedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);
        
        log.info("Soft deleted invoice: {}", invoice.getInvoiceNo());
    }
    
    /**
     * Get invoice statistics
     */
    @Transactional(readOnly = true)
    public InvoiceStats getStatistics() {
        long totalInvoices = invoiceRepository.countAll();
        long draftInvoices = invoiceRepository.countByStatus(InvoiceEntity.Status.DRAFT);
        long sentInvoices = invoiceRepository.countByStatus(InvoiceEntity.Status.SENT);
        long paidInvoices = invoiceRepository.countByStatus(InvoiceEntity.Status.PAID);
        long partiallyPaidInvoices = invoiceRepository.countByStatus(InvoiceEntity.Status.PARTIALLY_PAID);
        
        BigDecimal totalPaidAmount = invoiceRepository.sumPaidInvoices();
        BigDecimal totalPendingAmount = invoiceRepository.sumPendingAmount();
        
        return InvoiceStats.builder()
                .totalInvoices(totalInvoices)
                .draftInvoices(draftInvoices)
                .sentInvoices(sentInvoices)
                .paidInvoices(paidInvoices)
                .partiallyPaidInvoices(partiallyPaidInvoices)
                .totalPaidAmount(totalPaidAmount != null ? totalPaidAmount : BigDecimal.ZERO)
                .totalPendingAmount(totalPendingAmount != null ? totalPendingAmount : BigDecimal.ZERO)
                .build();
    }
    
    /**
     * Generate PDF for invoice
     */
    public byte[] generatePdf(InvoiceEntity invoice) throws Exception {
        return pdfService.generateInvoicePdf(invoice);
    }
    
    // Helper methods
    
    private boolean isAdmin(String userRole) {
        return "ADMIN".equalsIgnoreCase(userRole) || "SUPERADMIN".equalsIgnoreCase(userRole);
    }
    
    /**
     * Generate unique invoice number
     * Format: INV-YYYY-NNNN
     */
    private String generateInvoiceNumber() {
        int currentYear = Year.now().getValue();
        String prefix = "INV-" + currentYear + "-";
        
        long count = invoiceRepository.countByInvoiceNoPrefix(prefix + "%");
        String number = String.format("%04d", count + 1);
        
        return prefix + number;
    }
    
    private BigDecimal calculateTotalAmount(List<InvoiceItemEntity> items) {
        BigDecimal total = BigDecimal.ZERO;
        
        for (InvoiceItemEntity item : items) {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal taxPercent = item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO;
            
            BigDecimal lineSubtotal = quantity.multiply(unitPrice);
            BigDecimal taxAmount = lineSubtotal.multiply(taxPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            
            total = total.add(lineSubtotal).add(taxAmount);
        }
        
        return total.setScale(2, RoundingMode.HALF_UP);
    }
 // Update the recordPayment method in InvoiceService.java


    @Transactional
    public InvoiceEntity recordPayment(Long id, BigDecimal paymentAmount, String paymentMethod, 
                                        String transactionRef, String notes, Long userId) {
        InvoiceEntity invoice = getInvoiceById(id);
        
        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaidAmount = currentPaid.add(paymentAmount);
        
        invoice.setPaidAmount(newPaidAmount);
        
        // Update status based on payment
        BigDecimal totalAmount = invoice.getTotalAmount();
        if (newPaidAmount.compareTo(totalAmount) >= 0) {
            invoice.setStatus(InvoiceEntity.Status.PAID);
        } else if (newPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            invoice.setStatus(InvoiceEntity.Status.PARTIALLY_PAID);
        }
        
        invoice.setUpdatedAt(LocalDateTime.now());
        
        // Record payment history
        PaymentHistoryEntity paymentHistory = PaymentHistoryEntity.builder()
                .invoice(invoice)
                .amount(paymentAmount)
                .paymentMethod(paymentMethod)
                .transactionReference(transactionRef)
                .notes(notes)
                .recordedBy(userId)
                .paymentDate(LocalDateTime.now())
                .build();
        
        paymentHistoryRepository.save(paymentHistory);
        
        log.info("Recorded payment of {} for invoice {}", paymentAmount, invoice.getInvoiceNo());
        return invoiceRepository.save(invoice);
    }

    /**
     * Get payment history for an invoice
     */
    @Transactional(readOnly = true)
    public List<PaymentHistoryEntity> getPaymentHistory(Long invoiceId) {
        return paymentHistoryRepository.findByInvoiceIdOrderByPaymentDateDesc(invoiceId);
    }
    // Stats inner class
    @lombok.Data
    @lombok.Builder
    public static class InvoiceStats {
        private long totalInvoices;
        private long draftInvoices;
        private long sentInvoices;
        private long paidInvoices;
        private long partiallyPaidInvoices;
        private BigDecimal totalPaidAmount;
        private BigDecimal totalPendingAmount;
    }
}