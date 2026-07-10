package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceAttachmentEntity;
import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceRepository;
import com.istlgroup.istl_group_crm_backend.repo.PaymentHistoryRepository;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceAttachmentRepository;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.LoginRepo;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
//import com.istlgroup.istl_group_crm_backend.repo.ProjectAccessRepository;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Module;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Type;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookItemRepo;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {
    
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final CustomersRepo customerRepository;
    private final InvoicePdfService pdfService;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final OrderBookRepo orderBookRepo;
    private final OrderBookItemRepo orderBookItemRepo;
    private final ProjectRepository projectRepository;
    private final ProjectStatsService projectStatsService;
    private final ProjectAccessService projectAccessService;
    private final InvoiceAttachmentRepository attachmentRepository;
    private final LoginRepo loginRepo;
//    private final ProjectAccessRepository projectAccessRepository;
    private final MailService mailService;
    private final RoleHierarchyRepo roleHierarchyRepo;
    private final NotificationService notificationService;

   //  ── Approval notification target — configured in application.properties ──
    @org.springframework.beans.factory.annotation.Value("${invoice.approval.notification-emails:}")
    private String[] accountsNotificationEmails;

    /**
     * ✅ UPDATED: Get invoices with customer details populated
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

        // Normalize inputs — null-safe, empty strings treated as null
        String cleanGroup   = (groupId    != null && !groupId.trim().isEmpty())    ? groupId.trim()    : null;
        String cleanSub     = (subGroupId != null && !subGroupId.trim().isEmpty()) ? subGroupId.trim() : null;
        String cleanProject = (projectId  != null && !projectId.trim().isEmpty())  ? projectId.trim()  : null;
        String cleanSearch  = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;

        // Parse date range (yyyy-MM-dd from frontend)
        LocalDateTime from = (fromDate != null && !fromDate.isBlank())
                ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to   = (toDate   != null && !toDate.isBlank())
                ? LocalDate.parse(toDate).atTime(23, 59, 59) : null;

        // Normalize status to UPPERCASE with spaces (DB stores e.g. 'Partially Paid', not 'PARTIALLY_PAID')
        // Frontend dropdown sends 'PARTIALLY_PAID'; UPPER(db_value) comparison needs 'PARTIALLY PAID'
        String cleanStatus  = null;
        if (status != null && !status.trim().isEmpty() && !"all".equalsIgnoreCase(status.trim())) {
            cleanStatus = status.trim().toUpperCase().replace("_", " ");
        }

        Page<InvoiceEntity> invoices;

        if (cleanProject != null) {
            // Specific project — all roles see the same data when project is selected
            invoices = invoiceRepository.findFiltered(null, null, cleanProject, null, cleanStatus, cleanSearch, from, to, pageable);
        } else if (isAdmin) {
            // Admin — full scope, scoped by group/subGroup if provided
            invoices = invoiceRepository.findFiltered(cleanGroup, cleanSub, null, null, cleanStatus, cleanSearch, from, to, pageable);
        } else {
            // Non-admin — scope to accessible project IDs from project_access table
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                invoices = invoiceRepository.findFilteredAccessible(accessibleProjectIds, cleanGroup, cleanSub, cleanStatus, cleanSearch, from, to, pageable);
            } else {
                // No project grants at all — fall back to createdBy
                invoices = invoiceRepository.findFiltered(cleanGroup, cleanSub, null, userId, cleanStatus, cleanSearch, from, to, pageable);
            }
        }

        // Populate @Transient customer fields for display
        invoices.forEach(this::populateCustomerDetails);
        invoices.forEach(this::populateAttachmentInfo);
        return invoices;
    }
    
    /**
     * ✅ UPDATED: Get invoice by ID with customer details
     */
    @Transactional(readOnly = true)
    public InvoiceEntity getInvoiceById(Long id) {
        InvoiceEntity invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found with id: " + id));
        
        // ✅ NEW: Populate customer details
        populateCustomerDetails(invoice);
        
        return invoice;
    }
    
    /**
     * ✅ UPDATED: Get invoice by ID with items and customer details
     */
    @Transactional(readOnly = true)
    public InvoiceEntity getInvoiceByIdWithItems(Long id) {
        InvoiceEntity invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found with id: " + id));
        
        // Force load items to avoid lazy loading
        if (invoice.getItems() != null) {
            invoice.getItems().size();
        }
        
        // ✅ NEW: Populate customer details + attachment info
        populateCustomerDetails(invoice);
        populateAttachmentInfo(invoice);
        
        return invoice;
    }
    
    /**
     * ✅ NEW HELPER METHOD: Populate customer name and company name
     */
    private void populateCustomerDetails(InvoiceEntity invoice) {
        if (invoice != null && invoice.getCustomerId() != null) {
            customerRepository.findById(invoice.getCustomerId())
                .ifPresent(customer -> {
                    invoice.setCustomerName(customer.getName());
                    invoice.setCustomerCompanyName(customer.getCompanyName());
                });
        }
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
     * ✅ UPDATED: Create new invoice - returns invoice with customer details
     */
    @Transactional
    /**
     * Recomputes invoiced_qty for each order_book_item referenced in the given invoice items,
     * then persists the updated values. Called after every create / update / soft-delete.
     */
    private void syncOrderBookItemInvoicedQty(List<InvoiceItemEntity> items) {
        if (items == null || items.isEmpty()) return;
        // Collect distinct orderBookItemIds
        java.util.Set<Long> obItemIds = new java.util.HashSet<>();
        for (InvoiceItemEntity item : items) {
            if (item.getOrderBookItemId() != null) obItemIds.add(item.getOrderBookItemId());
        }
        for (Long obItemId : obItemIds) {
            orderBookItemRepo.findById(obItemId).ifPresent(obItem -> {
                // Sum all non-deleted invoice quantities for this order_book_item
                BigDecimal invoiced = invoiceItemRepository.sumInvoicedQtyByOrderBookItemId(obItemId);
                obItem.setInvoicedQty(invoiced != null ? invoiced : BigDecimal.ZERO);
                orderBookItemRepo.save(obItem);
            });
        }
    }

    /**
     * Validates that none of the invoice items exceed the remaining quantity
     * (order_book_item.quantity - invoiced_qty) before saving.
     * Throws IllegalArgumentException if over-invoiced.
     */
    private void validateInvoicedQty(List<InvoiceItemEntity> items, Long excludeInvoiceId) {
        if (items == null) return;
        for (InvoiceItemEntity item : items) {
            if (item.getOrderBookItemId() == null || item.getQuantity() == null) continue;
            orderBookItemRepo.findById(item.getOrderBookItemId()).ifPresent(obItem -> {
                BigDecimal total = obItem.getQuantity() != null ? obItem.getQuantity() : BigDecimal.ZERO;
                // When editing, use the query that already excludes this invoice's own items
                // so we only count what OTHER invoices have consumed.
                BigDecimal alreadyInvoiced;
                if (excludeInvoiceId != null) {
                    alreadyInvoiced = invoiceItemRepository
                        .sumInvoicedQtyForItemInInvoice(item.getOrderBookItemId(), excludeInvoiceId);
                } else {
                    alreadyInvoiced = invoiceItemRepository
                        .sumInvoicedQtyByOrderBookItemId(item.getOrderBookItemId());
                }
                if (alreadyInvoiced == null) alreadyInvoiced = BigDecimal.ZERO;
                BigDecimal remaining = total.subtract(alreadyInvoiced);
                if (item.getQuantity().compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(
                        "Item \"" + obItem.getItemName() + "\": requested " + item.getQuantity()
                        + " but only " + remaining + " remaining (total " + total
                        + ", already invoiced by others " + alreadyInvoiced + ")");
                }
            });
        }
    }

    public InvoiceEntity createInvoice(InvoiceEntity invoice, Long userId) {
        return createInvoice(invoice, userId, null);
    }

    public InvoiceEntity createInvoice(InvoiceEntity invoice, Long userId, String userRole) {
        try {
            log.info("Creating invoice for user: {} role: {}", userId, userRole);

            // Invoice number is derived from the DB-generated id (see below).
            // Use a temp placeholder to satisfy any NOT NULL constraint.
            invoice.setInvoiceNo("__TEMP_INV_" + System.nanoTime() + "__");

            // Set metadata
            invoice.setCreatedBy(userId);
            invoice.setCreatedAt(LocalDateTime.now());
            invoice.setUpdatedAt(LocalDateTime.now());

            // ── Approval workflow ──────────────────────────────────────────────
            // Non-privileged roles (not ADMIN, SUPERADMIN, or ACCOUNTS_*) must go
            // through accounts approval regardless of what status the client sent.
            boolean isPrivileged = isPrivilegedRole(userRole);
            if (!isPrivileged) {
                invoice.setStatus(InvoiceEntity.Status.PENDING_APPROVAL);
                log.info("Non-privileged role [{}] — forcing status to PENDING_APPROVAL", userRole);
            } else {
                // Privileged roles keep their chosen status, default to DRAFT
                if (invoice.getStatus() == null || invoice.getStatus().isEmpty()) {
                    invoice.setStatus(InvoiceEntity.Status.DRAFT);
                }
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

            // Validate quantities against order book remaining qty
            validateInvoicedQty(itemsToSave, null);

            // Clear items to prevent cascade issues
            invoice.setItems(new ArrayList<>());

            // Calculate total
            BigDecimal totalAmount = calculateTotalAmount(itemsToSave);
            invoice.setTotalAmount(totalAmount);

            // Save invoice — DB assigns auto-increment id
            InvoiceEntity savedInvoice = invoiceRepository.save(invoice);
            // Derive invoice number from the DB id — only if no real code exists yet.
            // This guards against overwriting codes on records created before this logic.
            String existingInvNo = savedInvoice.getInvoiceNo();
            if (existingInvNo == null || existingInvNo.isBlank() || existingInvNo.startsWith("__TEMP_")) {
                String invoiceNo = String.format("INV-%d-%04d",
                    java.time.Year.now().getValue(), savedInvoice.getId());
                savedInvoice.setInvoiceNo(invoiceNo);
                savedInvoice = invoiceRepository.save(savedInvoice);
            }
            log.info("Invoice saved with ID: {} and number: {}", savedInvoice.getId(), savedInvoice.getInvoiceNo());

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
                // Update invoiced_qty on order book items
                syncOrderBookItemInvoicedQty(savedItems);
            }

            // ✅ Populate customer details before returning
            populateCustomerDetails(savedInvoice);

            // ── Sync project invoice stats (replaces dropped DB trigger) ────
            if (savedInvoice.getProjectId() != null && !savedInvoice.getProjectId().isBlank()) {
                syncProjectInvoiceStats(savedInvoice.getProjectId());
            }

            // ── Send notification email to accounts team if PENDING_APPROVAL ──
            if (InvoiceEntity.Status.PENDING_APPROVAL.equals(savedInvoice.getStatus())) {
                sendPendingApprovalEmail(savedInvoice, userId);
            }

            log.info("Invoice created successfully: {}", savedInvoice.getInvoiceNo());

            // ── NOTIFICATION: notify APPROVERS that an invoice is pending approval.
            //    The creator is intentionally NOT notified at creation — they are
            //    only notified after the invoice is approved or rejected.
            try {
                if (InvoiceEntity.Status.PENDING_APPROVAL.equals(savedInvoice.getStatus())) {
                    notifyApproversOfPendingInvoice(savedInvoice);
                }
            } catch (Exception e) {
                log.warn("Pending-approval notification failed: {}", e.getMessage());
            }

            return savedInvoice;

        } catch (Exception e) {
            log.error("Error creating invoice: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create invoice: " + e.getMessage());
        }
    }
    
    /**
     * Get all order book items for a customer
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getOrderBookItemsByCustomer(Long customerId) {
        try {
            log.info("Fetching order book items for customer: {}", customerId);
            
            // Find all active order books for this customer
            List<OrderBookEntity> orderBooks = orderBookRepo.findByCustomerIdAndDeletedAtIsNull(customerId);
            
            if (orderBooks.isEmpty()) {
                log.info("No order books found for customer: {}", customerId);
                return new ArrayList<>();
            }
            
            List<Map<String, Object>> allItems = new ArrayList<>();
            
            // Collect items from all order books
            for (OrderBookEntity orderBook : orderBooks) {
                List<OrderBookItemEntity> items = orderBookItemRepo
                    .findByOrderBookIdOrderByLineNo(orderBook.getId());
                
                for (OrderBookItemEntity item : items) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("orderBookId", orderBook.getId());
                    itemMap.put("orderBookNo", orderBook.getOrderBookNo());
                    itemMap.put("itemName", item.getItemName());
                    itemMap.put("specification", item.getSpecification());
                    itemMap.put("description", item.getDescription());
                    itemMap.put("quantity", item.getQuantity());
                    itemMap.put("unit", item.getUnit());
                    itemMap.put("unitPrice", item.getUnitPrice());
                    itemMap.put("taxPercent", item.getTaxPercent());
                    itemMap.put("discountPercent", item.getDiscountPercent());
                    // FIX: include already-invoiced qty so frontend shows correct
                    // remaining quantity in the order book table and caps the input
                    java.math.BigDecimal invoicedQty = item.getInvoicedQty() != null
                            ? item.getInvoicedQty() : java.math.BigDecimal.ZERO;
                    itemMap.put("invoicedQty", invoicedQty);
                    
                    allItems.add(itemMap);
                }
            }
            
            log.info("Found {} order book items for customer {}", allItems.size(), customerId);
            return allItems;
            
        } catch (Exception e) {
            log.error("Error fetching order book items for customer: {}", customerId, e);
            throw new RuntimeException("Failed to fetch order book items: " + e.getMessage());
        }
    }
    
    /**
     * Get order book items by project ID for invoice item selection
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getOrderBookItemsByProject(String projectId) {
        try {
            // Find order books for this project (via customer)
            CustomersEntity customer = customerRepository.findByProjectId(projectId)
                    .orElseThrow(() -> new RuntimeException("Customer not found for project: " + projectId));
            
            // Get all active order books for this customer
            List<OrderBookEntity> orderBooks = orderBookRepo.findByCustomerIdAndDeletedAtIsNull(customer.getId());
            
            if (orderBooks.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Collect all items from all order books
            List<Map<String, Object>> allItems = new ArrayList<>();
            
            for (OrderBookEntity orderBook : orderBooks) {
                List<OrderBookItemEntity> items = orderBookItemRepo.findByOrderBookIdWithCalculatedFields(orderBook.getId());
                
                for (OrderBookItemEntity item : items) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("orderBookId", item.getOrderBookId());
                    itemMap.put("orderBookNo", orderBook.getOrderBookNo());
                    itemMap.put("itemName", item.getItemName());
                    itemMap.put("specification", item.getSpecification());
                    itemMap.put("description", item.getDescription());
                    itemMap.put("quantity", item.getQuantity());
                    itemMap.put("unit", item.getUnit());
                    itemMap.put("unitPrice", item.getUnitPrice());
                    itemMap.put("taxPercent", item.getTaxPercent());
                    itemMap.put("discountPercent", item.getDiscountPercent());
                    // FIX: include already-invoiced qty
                    java.math.BigDecimal invoicedQtyP = item.getInvoicedQty() != null
                            ? item.getInvoicedQty() : java.math.BigDecimal.ZERO;
                    itemMap.put("invoicedQty", invoicedQtyP);
                    
                    allItems.add(itemMap);
                }
            }
            
            return allItems;
            
        } catch (Exception e) {
            log.error("Error fetching order book items for project: {}", projectId, e);
            throw new RuntimeException("Failed to fetch order book items: " + e.getMessage());
        }
    }
    
    /**
     * ✅ UPDATED: Update invoice - returns invoice with customer details
     */
    @Transactional
    public InvoiceEntity updateInvoice(Long id, InvoiceEntity updatedInvoice) {
        InvoiceEntity existing = getInvoiceById(id);

        // Capture old project before changing it
        String oldInvoiceProjectId = existing.getProjectId();

        // Update fields
        existing.setCustomerId(updatedInvoice.getCustomerId());
        existing.setProjectId(updatedInvoice.getProjectId());
        existing.setGroupId(updatedInvoice.getGroupId());
        existing.setSubGroupId(updatedInvoice.getSubGroupId());
        existing.setInvoiceDate(updatedInvoice.getInvoiceDate());
        existing.setDueDate(updatedInvoice.getDueDate());
        existing.setStatus(updatedInvoice.getStatus());
        existing.setCompany(updatedInvoice.getCompany());
        existing.setInvoiceNumber(updatedInvoice.getInvoiceNumber()); // Fix: persist Tally Invoice Number
        
        // Update items if provided
        if (updatedInvoice.getItems() != null) {
            // Validate new quantities before deleting old items
            validateInvoicedQty(updatedInvoice.getItems(), id);
            // Get old items to re-sync their order book items after delete
            List<InvoiceItemEntity> oldItems = invoiceItemRepository.findByInvoiceId(id);
            invoiceItemRepository.deleteByInvoiceId(id);
            // Sync order book items that were on the old invoice (decrement)
            syncOrderBookItemInvoicedQty(oldItems);
            
            for (InvoiceItemEntity item : updatedInvoice.getItems()) {
                item.setInvoice(existing);
                if (item.getQuantity() == null) item.setQuantity(BigDecimal.ONE);
                if (item.getUnitPrice() == null) item.setUnitPrice(BigDecimal.ZERO);
                if (item.getTaxPercent() == null) item.setTaxPercent(BigDecimal.valueOf(18));
            }
            List<InvoiceItemEntity> savedItems = invoiceItemRepository.saveAll(updatedInvoice.getItems());
            // Sync order book items with new quantities
            syncOrderBookItemInvoicedQty(savedItems);
            
            // Recalculate total
            BigDecimal totalAmount = calculateTotalAmount(updatedInvoice.getItems());
            existing.setTotalAmount(totalAmount);
        }
        
        existing.setUpdatedAt(LocalDateTime.now());
        InvoiceEntity saved = invoiceRepository.save(existing);
        
        // ✅ NEW: Populate customer details before returning
        populateCustomerDetails(saved);

        // ── Sync project invoice stats (replaces dropped DB trigger) ────────
        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            syncProjectInvoiceStats(saved.getProjectId());
        }
        // ── Sync old project if project assignment changed ────────────────────
        if (oldInvoiceProjectId != null && !oldInvoiceProjectId.isBlank()
                && !oldInvoiceProjectId.equals(saved.getProjectId())) {
            syncProjectInvoiceStats(oldInvoiceProjectId);
            log.info("Synced old project [{}] invoice stats after invoice [{}] moved",
                     oldInvoiceProjectId, saved.getInvoiceNo());
        }

        return saved;
    }

    /**
     * ✅ UPDATED: Update invoice status - returns invoice with customer details
     */
    @Transactional
    public InvoiceEntity updateStatus(Long id, String newStatus) {
        InvoiceEntity invoice = getInvoiceById(id);
        invoice.setStatus(newStatus);
        invoice.setUpdatedAt(LocalDateTime.now());
        
        log.info("Updated invoice {} status to: {}", invoice.getInvoiceNo(), newStatus);
        InvoiceEntity saved = invoiceRepository.save(invoice);

        // ── NOTIFICATION: invoice rejected → notify the creator ──
        try {
            if ("Rejected".equalsIgnoreCase(newStatus) && saved.getCreatedBy() != null) {
                notificationService.createNotification(
                    saved.getCreatedBy(),
                    "Invoice rejected",
                    "Invoice " + saved.getInvoiceNo() + " has been rejected.",
                    Module.INVOICE, saved.getId(), Type.INVOICE_REJECTED);
            }
        } catch (Exception e) {
            log.warn("Invoice-rejected notification failed: {}", e.getMessage());
        }
        
        // ✅ NEW: Populate customer details before returning
        populateCustomerDetails(saved);

        // ── Sync project invoice stats (replaces dropped DB trigger) ────────
        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            syncProjectInvoiceStats(saved.getProjectId());
        }
        
        return saved;
    }
    
    /**
     * ✅ UPDATED: Record payment - returns invoice with customer details
     */
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
        InvoiceEntity saved = invoiceRepository.save(invoice);

        // ── NOTIFICATION: payment received → notify the creator ──
        try {
            if (saved.getCreatedBy() != null) {
                notificationService.createNotification(
                    saved.getCreatedBy(),
                    "Payment received",
                    "A payment of " + paymentAmount + " was recorded on invoice " + saved.getInvoiceNo() + ".",
                    Module.INVOICE, saved.getId(), Type.INVOICE_PAYMENT);
            }
        } catch (Exception e) {
            log.warn("Payment-received notification failed: {}", e.getMessage());
        }
        
        // ✅ NEW: Populate customer details before returning
        populateCustomerDetails(saved);

        // ── Direct incremental update on projects table ─────────────────────
        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            try {
                projectRepository.incrementProjectPaidInvoiceValue(
                    saved.getProjectId(), paymentAmount);
                log.info("Incremented paid_invoice_value by {} for project [{}]",
                         paymentAmount, saved.getProjectId());
            } catch (Exception e) {
                log.error("Failed to update project invoice stats for [{}]: {}",
                          saved.getProjectId(), e.getMessage(), e);
            }
        }
        
        return saved;
    }

    /**
     * Get payment history for an invoice
     */
    @Transactional(readOnly = true)
    public List<PaymentHistoryEntity> getPaymentHistory(Long invoiceId) {
        return paymentHistoryRepository.findByInvoiceIdOrderByPaymentDateDesc(invoiceId);
    }
    
    /**
     * Soft delete invoice
     */
    @Transactional
    public void deleteInvoice(Long id) {
        InvoiceEntity invoice = getInvoiceById(id);
        String projectId = invoice.getProjectId();
        // Get items before soft-deleting so we can decrement invoiced_qty
        List<InvoiceItemEntity> items = invoiceItemRepository.findByInvoiceId(id);
        invoice.setDeletedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);
        // Decrement invoiced_qty on all referenced order book items
        syncOrderBookItemInvoicedQty(items);

        // ── Sync project invoice stats (replaces dropped DB trigger) ────────
        if (projectId != null && !projectId.isBlank()) {
            syncProjectInvoiceStats(projectId);
        }

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
    
    @Transactional(readOnly = true)
    public Map<String, Object> getInvoiceSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy
    ) {
        // Delegate to the role-aware overload with a null role (non-admin path kept)
        return getInvoiceSummary(groupId, subGroupId, projectId, createdBy, null);
    }

    /**
     * Role-aware summary used by the KPI cards.
     * - Admin: sees all invoices in scope (no project restriction)
     * - Non-admin: sees only invoices from projects they have access to
     *   (via project_access table), scoped to group/subGroup if provided.
     * Now supports searchTerm and statusFilter so KPI cards react to all active filters.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getInvoiceSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy,
            String userRole
    ) {
        return getInvoiceSummary(groupId, subGroupId, projectId, createdBy, userRole, null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getInvoiceSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy,
            String userRole,
            String searchTerm,
            String statusFilter
    ) {
        return getInvoiceSummary(groupId, subGroupId, projectId, createdBy, userRole, searchTerm, statusFilter, null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getInvoiceSummary(
            String groupId,
            String subGroupId,
            String projectId,
            Long createdBy,
            String userRole,
            String searchTerm,
            String statusFilter,
            String fromDate,
            String toDate
    ) {
        // Reuse findFiltered (which already has date support) to guarantee KPIs match table exactly
        String cleanGroup   = (groupId    != null && !groupId.trim().isEmpty())    ? groupId.trim()    : null;
        String cleanSub     = (subGroupId != null && !subGroupId.trim().isEmpty()) ? subGroupId.trim() : null;
        String cleanProject = (projectId  != null && !projectId.trim().isEmpty())  ? projectId.trim()  : null;
        String cleanSearch  = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;
        String cleanStatus  = (statusFilter != null && !statusFilter.trim().isEmpty() && !"all".equalsIgnoreCase(statusFilter.trim()))
                              ? statusFilter.trim().toUpperCase().replace("_", " ") : null;

        LocalDateTime from = (fromDate != null && !fromDate.isBlank())
                ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to   = (toDate   != null && !toDate.isBlank())
                ? LocalDate.parse(toDate).atTime(23, 59, 59) : null;

        boolean isAdmin = isAdmin(userRole);
        Pageable all = Pageable.unpaged();

        List<InvoiceEntity> invoices;
        if (cleanProject != null) {
            invoices = invoiceRepository.findFiltered(null, null, cleanProject, null, cleanStatus, cleanSearch, from, to, all).getContent();
        } else if (isAdmin) {
            invoices = invoiceRepository.findFiltered(cleanGroup, cleanSub, null, null, cleanStatus, cleanSearch, from, to, all).getContent();
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(createdBy, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                invoices = invoiceRepository.findFilteredAccessible(accessibleProjectIds, cleanGroup, cleanSub, cleanStatus, cleanSearch, from, to, all).getContent();
            } else {
                invoices = invoiceRepository.findFiltered(cleanGroup, cleanSub, null, createdBy, cleanStatus, cleanSearch, from, to, all).getContent();
            }
        }

        long totalCount    = invoices.size();
        long paidCount     = invoices.stream().filter(i -> "PAID".equalsIgnoreCase(i.getStatus())).count();
        long pendingCount  = invoices.stream().filter(i -> {
            String s = i.getStatus() == null ? "" : i.getStatus().toUpperCase();
            return s.equals("SENT") || s.contains("PARTIALLY");
        }).count();
        BigDecimal totalAmount   = invoices.stream().map(i -> i.getTotalAmount()   != null ? i.getTotalAmount()   : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidAmount    = invoices.stream().map(i -> i.getPaidAmount()    != null ? i.getPaidAmount()    : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingAmount = invoices.stream().map(i -> i.getBalanceAmount() != null ? i.getBalanceAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        result.put("totalCount",    totalCount);
        result.put("paidCount",     paidCount);
        result.put("pendingCount",  pendingCount);
        result.put("totalAmount",   totalAmount);
        result.put("paidAmount",    paidAmount);
        result.put("pendingAmount", pendingAmount);
        return result;
    }

    // =========================================================================
    // SYNC PROJECT INVOICE STATS  (replaces dropped DB triggers)
    // =========================================================================

    @Transactional
    public void syncProjectInvoiceStats(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            log.warn("syncProjectInvoiceStats called with null/blank projectId – skipping");
            return;
        }
        try {
            projectStatsService.updateProjectAfterInvoiceChange(projectId);
            log.info("Synced invoice stats for project [{}] via ProjectStatsService", projectId);
        } catch (Exception e) {
            log.error("Failed to sync invoice stats for project [{}]: {}", projectId, e.getMessage(), e);
        }
    }

    /**
     * ✅ UPDATED: Get unpaid invoices for a customer with customer details
     */
    @Transactional(readOnly = true)
    // =========================================================================
    // OUTSTANDINGS — backs GET /invoices/outstandings
    // Mirrors BillService.getOutstandings. The repo already has all the
    // findAllForOutstandings* queries; this method was the missing piece.
    // Returns { allInvoices: [...], outstanding: [...] }; the frontend uses
    // the "outstanding" list (invoices not Paid/Cancelled with a balance > 0).
    // =========================================================================
    public Map<String, Object> getOutstandings(
            String projectId, String groupId, String subGroupId,
            Long userId, String userRole) {

        boolean isAdmin = isPrivilegedRole(userRole);
        List<InvoiceEntity> all;

        if (projectId != null && !projectId.isBlank()) {
            all = invoiceRepository.findAllForOutstandingsByProject(projectId);
        } else if (isAdmin) {
            if (subGroupId != null && !subGroupId.isBlank()) {
                all = invoiceRepository.findAllForOutstandingsBySubGroup(groupId, subGroupId);
            } else if (groupId != null && !groupId.isBlank()) {
                all = invoiceRepository.findAllForOutstandingsByGroup(groupId);
            } else {
                all = invoiceRepository.findAllForOutstandings();
            }
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            boolean hasAccess = accessibleProjectIds != null && !accessibleProjectIds.isEmpty();
            if (hasAccess) {
                if (subGroupId != null && !subGroupId.isBlank()) {
                    all = invoiceRepository.findAllForOutstandingsBySubGroupAndAccessibleProjects(groupId, subGroupId, accessibleProjectIds);
                } else if (groupId != null && !groupId.isBlank()) {
                    all = invoiceRepository.findAllForOutstandingsByGroupAndAccessibleProjects(groupId, accessibleProjectIds);
                } else {
                    all = invoiceRepository.findAllForOutstandingsByAccessibleProjects(accessibleProjectIds);
                }
            } else {
                all = java.util.Collections.emptyList();
            }
        }

        // Populate customer/project display fields used by the outstandings table.
        for (InvoiceEntity inv : all) {
            try { populateCustomerDetails(inv); } catch (Exception ignored) { }
        }

        // Outstanding = not Paid/Cancelled AND remaining balance > 0.01
        List<InvoiceEntity> outstanding = all.stream()
                .filter(inv -> {
                    String s = inv.getStatus() == null ? "" : inv.getStatus();
                    if (s.equalsIgnoreCase(InvoiceEntity.Status.PAID)
                            || s.equalsIgnoreCase(InvoiceEntity.Status.CANCELLED)) {
                        return false;
                    }
                    BigDecimal total = inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
                    BigDecimal paid  = inv.getPaidAmount()  != null ? inv.getPaidAmount()  : BigDecimal.ZERO;
                    return total.subtract(paid).compareTo(new BigDecimal("0.01")) > 0;
                })
                .toList();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("allInvoices", all);
        result.put("outstanding", outstanding);
        return result;
    }

    public List<InvoiceEntity> getUnpaidInvoicesForCustomer(Long customerId) {
        List<InvoiceEntity> invoices = invoiceRepository.findUnpaidInvoicesByCustomerId(customerId);
        
        // ✅ NEW: Populate customer details for each invoice
        invoices.forEach(this::populateCustomerDetails);
        
        return invoices;
    }

    /**
     * ✅ UPDATED: Get unpaid invoices by project with customer details
     */
    @Transactional(readOnly = true)
    public List<InvoiceEntity> getUnpaidInvoicesByProject(String projectId) {
        List<InvoiceEntity> invoices = invoiceRepository.findUnpaidInvoicesByProjectId(projectId);
        
        // ✅ NEW: Populate customer details for each invoice
        invoices.forEach(this::populateCustomerDetails);
        
        return invoices;
    }
    
    // Helper methods
    
    /**
     * Returns true when the user should see ALL data without project-access filtering.
     * Rules:
     *   1. ADMIN / SUPERADMIN always bypass.
     *   2. Any role whose name starts with ACCOUNTS_ bypasses (ACCOUNTS_EXECUTIVE,
     *      ACCOUNTS_MANAGER, ACCOUNTS_CFO, or any future ACCOUNTS_* role).
     *   3. Any role whose level_order in role_hierarchy is <= 2 bypasses
     *      (i.e. top-level roles configured dynamically in the DB).
     */
    private boolean isAdmin(String userRole) {
        if (userRole == null) return false;
        String r = userRole.trim().toUpperCase();
        if (r.equals("ADMIN") || r.equals("SUPERADMIN")) return true;
        if (r.startsWith("ACCOUNTS_")) return true;
        // Dynamic check: level_order <= 2 in role_hierarchy table
        return roleHierarchyRepo.findLevelOrderByRoleName(RoleNormalizer.normalize(userRole))
                .map(level -> level <= 2)
                .orElse(false);
    }

    /** Returns true for roles that bypass the approval workflow */
    private boolean isPrivilegedRole(String role) {
        if (role == null) return false;
        return "ADMIN".equalsIgnoreCase(role)
            || "SUPERADMIN".equalsIgnoreCase(role)
            || role.toUpperCase().startsWith("ACCOUNTS_");
    }

    // =========================================================================
    // INVOICE APPROVAL WORKFLOW
    // =========================================================================

    /**
     * Called by accounts team to approve a PENDING_APPROVAL invoice and upload
     * the official invoice file. Saves file as BLOB, updates status to APPROVED,
     * then sends notification emails.
     */
    @Transactional
    public InvoiceEntity approveInvoice(Long invoiceId, byte[] fileData, String fileName,
                                         String fileType, Long approverUserId, String approverName,
                                         String notes, String tallyNumber) {
        InvoiceEntity invoice = getInvoiceById(invoiceId);

        if (!InvoiceEntity.Status.PENDING_APPROVAL.equals(invoice.getStatus())) {
            throw new RuntimeException("Invoice is not in PENDING_APPROVAL status. Current status: " + invoice.getStatus());
        }

        // Delete any previous attachment for this invoice (re-upload case)
        if (attachmentRepository.existsByInvoiceId(invoiceId)) {
            attachmentRepository.deleteByInvoiceId(invoiceId);
        }

        // Save file as BLOB
        InvoiceAttachmentEntity attachment = InvoiceAttachmentEntity.builder()
                .invoiceId(invoiceId)
                .fileName(fileName)
                .fileType(fileType)
                .fileData(fileData)
                .uploadedBy(approverUserId)
                .uploadedByName(approverName)
                .uploadedAt(LocalDateTime.now())
                .notes(notes)
                .build();
        attachmentRepository.save(attachment);

        // Persist Tally number if provided by accounts team
        if (tallyNumber != null && !tallyNumber.isBlank()) {
            invoice.setInvoiceNumber(tallyNumber);
        }

        // Update invoice status to APPROVED
        invoice.setStatus(InvoiceEntity.Status.APPROVED);
        invoice.setUpdatedAt(LocalDateTime.now());
        InvoiceEntity saved = invoiceRepository.save(invoice);

        populateCustomerDetails(saved);
        saved.setHasAttachment(true);
        saved.setAttachmentFileName(fileName);

        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            syncProjectInvoiceStats(saved.getProjectId());
        }

        // Send approval notification emails
        sendApprovalNotificationEmails(saved, approverName, fileName);

        log.info("Invoice {} approved by {} — file: {}, tally: {}", saved.getInvoiceNo(), approverName, fileName, tallyNumber);

        // ── NOTIFICATION: invoice approved → notify the creator ──
        try {
            if (saved.getCreatedBy() != null) {
                String when = LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
                notificationService.createNotification(
                    saved.getCreatedBy(),
                    "Invoice approved",
                    "Invoice " + saved.getInvoiceNo() + " was approved by " + approverName
                        + " on " + when + ". Status: Approved.",
                    Module.INVOICE, saved.getId(), Type.INVOICE_APPROVED);
            }
        } catch (Exception e) {
            log.warn("Invoice-approved notification failed: {}", e.getMessage());
        }

        return saved;
    }

    /**
     * Fetch the attachment BLOB for download.
     */
    @Transactional(readOnly = true)
    public InvoiceAttachmentEntity getAttachment(Long invoiceId) {
        return attachmentRepository.findLatestByInvoiceId(invoiceId)
                .orElseThrow(() -> new RuntimeException("No attachment found for invoice id: " + invoiceId));
    }

    /** Populate hasAttachment and attachmentFileName on a single invoice */
    private void populateAttachmentInfo(InvoiceEntity invoice) {
        if (invoice == null) return;
        attachmentRepository.findLatestByInvoiceId(invoice.getId()).ifPresent(a -> {
            invoice.setHasAttachment(true);
            invoice.setAttachmentFileName(a.getFileName());
        });
    }

    // =========================================================================
    // EMAIL NOTIFICATIONS
    // =========================================================================

    /** Notify accounts team that a new invoice is waiting for approval */
    private void sendPendingApprovalEmail(InvoiceEntity invoice, Long creatorUserId) {
        try {
            String creatorName = loginRepo.findNameById(creatorUserId).orElse("A team member");
            String subject = "📋 Invoice Approval Required — " + invoice.getInvoiceNo();
            String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e5e7eb;border-radius:8px;'>"
                + "<h2 style='color:#1e40af;margin-bottom:8px;'>Invoice Pending Approval</h2>"
                + "<p style='color:#374151;'>A new invoice has been submitted and requires your approval.</p>"
                + "<table style='width:100%;border-collapse:collapse;margin:20px 0;'>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;width:40%;'>Invoice No</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getInvoiceNo() + "</td></tr>"
                + (invoice.getInvoiceNumber() != null ? "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Tally Ref</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getInvoiceNumber() + "</td></tr>" : "")
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Submitted By</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + creatorName + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Customer</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + nvl(invoice.getCustomerName()) + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Total Amount</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>₹" + invoice.getTotalAmount() + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Invoice Date</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getInvoiceDate() + "</td></tr>"
                + "</table>"
                + "<p style='color:#6b7280;font-size:13px;'>Please log in to the CRM and approve this invoice from the Invoices page.</p>"
                + "<p style='margin:4px 0 0;'><a href='https://crm.sesolaenergy.com' style='color:#1e40af;font-weight:600;font-size:13px;'>crm.sesolaenergy.com</a></p>"
                + "<p style='color:#9ca3af;font-size:11px;margin-top:20px;'>SESOLA CRM — Automated Notification</p>"
                + "</div>";

            if (accountsNotificationEmails == null || accountsNotificationEmails.length == 0) {
                log.warn("invoice.approval.notification-emails is not configured — skipping pending-approval email for invoice {}", invoice.getInvoiceNo());
                return;
            }
            for (String rawEmail : accountsNotificationEmails) {
                String email = rawEmail == null ? "" : rawEmail.trim();
                if (email.isEmpty()) continue;
                try {
                    mailService.sendEmail(email, subject, body);
                    log.info("Pending approval email sent to {} for invoice {}", email, invoice.getInvoiceNo());
                } catch (Exception ex) {
                    log.error("Failed to send pending approval email to {}: {}", email, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to send pending approval email for invoice {}: {}", invoice.getInvoiceNo(), e.getMessage());
        }
    }

    /** Notify creator + all project-access users that the invoice has been approved */
    private void sendApprovalNotificationEmails(InvoiceEntity invoice, String approverName, String fileName) {
        try {
            if (invoice.getCreatedBy() == null) return;
            com.istlgroup.istl_group_crm_backend.entity.LoginEntity creator =
                loginRepo.findById(invoice.getCreatedBy()).orElse(null);
            if (creator == null || creator.getEmail() == null || creator.getEmail().isBlank()) return;

            String creatorName = creator.getName() != null ? creator.getName() : "Team";
            String subject = "✅ Your Invoice Has Been Approved — " + invoice.getInvoiceNo();
            String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e5e7eb;border-radius:8px;'>"
                + "<h2 style='color:#065f46;margin-bottom:8px;'>Invoice Approved</h2>"
                + "<p style='color:#374151;'>Hi " + creatorName + ",</p>"
                + "<p style='color:#374151;'>Your invoice has been reviewed and approved by the Accounts team. The official invoice file has been attached to the record.</p>"
                + "<table style='width:100%;border-collapse:collapse;margin:20px 0;'>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;width:40%;'>Invoice No</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getInvoiceNo() + "</td></tr>"
                + (invoice.getInvoiceNumber() != null && !invoice.getInvoiceNumber().isBlank()
                    ? "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Tally Ref</td>"
                    + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getInvoiceNumber() + "</td></tr>" : "")
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Approved By</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + approverName + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Customer</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + nvl(invoice.getCustomerName()) + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Total Amount</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>₹" + invoice.getTotalAmount() + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Attached File</td>"
                + "<td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + fileName + "</td></tr>"
                + "</table>"
                + "<p style='color:#6b7280;font-size:13px;'>You can log in to the CRM and download the approved invoice file from the Invoices page.</p>"
                + "<p style='margin:4px 0 0;'><a href='https://crm.sesolaenergy.com' style='color:#1e40af;font-weight:600;font-size:13px;'>crm.sesolaenergy.com</a></p>"
                + "<p style='color:#9ca3af;font-size:11px;margin-top:20px;'>SESOLA CRM — Automated Notification</p>"
                + "</div>";

            mailService.sendEmail(creator.getEmail().trim(), subject, body);
            log.info("Approval return mail sent to creator {} for invoice {}", creator.getEmail(), invoice.getInvoiceNo());
        } catch (Exception e) {
            log.error("Failed to send approval return mail for invoice {}: {}", invoice.getInvoiceNo(), e.getMessage());
        }
    }

    // =========================================================================
    // INVOICE REJECTION WORKFLOW  (mirrors approve)
    // =========================================================================

    /**
     * Called by accounts team to reject a PENDING_APPROVAL invoice.
     * Sets status to REJECTED, emails the creator, and raises an in-app
     * notification to the creator with the status, approver and timestamp.
     */
    public InvoiceEntity rejectInvoice(Long invoiceId, Long approverUserId, String approverName, String reason) {
        InvoiceEntity invoice = getInvoiceById(invoiceId);

        if (!InvoiceEntity.Status.PENDING_APPROVAL.equals(invoice.getStatus())) {
            throw new RuntimeException("Invoice is not in PENDING_APPROVAL status. Current status: " + invoice.getStatus());
        }

        invoice.setStatus(InvoiceEntity.Status.REJECTED);
        invoice.setUpdatedAt(LocalDateTime.now());
        InvoiceEntity saved = invoiceRepository.save(invoice);
        populateCustomerDetails(saved);

        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            try { syncProjectInvoiceStats(saved.getProjectId()); } catch (Exception ignored) { }
        }

        // Email the creator about the rejection
        sendRejectionNotificationEmail(saved, approverName, reason);

        log.info("Invoice {} rejected by {}", saved.getInvoiceNo(), approverName);

        // In-app notification to the creator
        try {
            if (saved.getCreatedBy() != null) {
                String when = LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
                String msg = "Invoice " + saved.getInvoiceNo() + " was rejected by " + approverName
                        + " on " + when + ". Status: Rejected."
                        + (reason != null && !reason.isBlank() ? " Reason: " + reason : "");
                notificationService.createNotification(
                    saved.getCreatedBy(),
                    "Invoice rejected",
                    msg,
                    Module.INVOICE, saved.getId(), Type.INVOICE_REJECTED);
            }
        } catch (Exception e) {
            log.warn("Invoice-rejected notification failed: {}", e.getMessage());
        }

        return saved;
    }

    /** In-app notification to all approver users that an invoice awaits approval. */
    private void notifyApproversOfPendingInvoice(InvoiceEntity invoice) {
        String creatorName = "A user";
        if (invoice.getCreatedBy() != null) {
            LoginEntity creator = loginRepo.findById(invoice.getCreatedBy()).orElse(null);
            if (creator != null && creator.getName() != null) creatorName = creator.getName();
        }
        for (LoginEntity u : loginRepo.findAll()) {
            boolean active = (u.getIs_active() == null) || (u.getIs_active() == 1L);
            // Only users whose role starts with ACCOUNTS_ receive the pending-approval
            // notification (Admin / Superadmin are intentionally excluded).
            boolean isApprover = u.getRole() != null && u.getRole().toUpperCase().startsWith("ACCOUNTS_");
            boolean notCreator = invoice.getCreatedBy() == null || !u.getId().equals(invoice.getCreatedBy());
            if (active && isApprover && notCreator) {
                try {
                    notificationService.createNotification(
                        u.getId(),
                        "Invoice pending approval",
                        "Invoice " + invoice.getInvoiceNo() + " from " + creatorName + " is pending your approval.",
                        Module.INVOICE, invoice.getId(), Type.INVOICE_PENDING_APPROVAL);
                } catch (Exception ex) {
                    log.warn("Pending-approval notification to user {} failed: {}", u.getId(), ex.getMessage());
                }
            }
        }
    }

    /** Email the creator that their invoice was rejected. */
    private void sendRejectionNotificationEmail(InvoiceEntity invoice, String approverName, String reason) {
        try {
            if (invoice.getCreatedBy() == null) return;
            LoginEntity creator = loginRepo.findById(invoice.getCreatedBy()).orElse(null);
            if (creator == null || creator.getEmail() == null || creator.getEmail().isBlank()) return;

            String creatorName = creator.getName() != null ? creator.getName() : "Team";
            String when = LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
            String subject = "❌ Your Invoice Has Been Rejected — " + invoice.getInvoiceNo();
            String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e5e7eb;border-radius:8px;'>"
                + "<h2 style='color:#991b1b;margin-bottom:8px;'>Invoice Rejected</h2>"
                + "<p style='color:#374151;'>Hi " + creatorName + ",</p>"
                + "<p style='color:#374151;'>Your invoice has been reviewed and <strong>rejected</strong> by the Accounts team.</p>"
                + "<table style='width:100%;border-collapse:collapse;margin:20px 0;'>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;width:40%;'>Invoice No</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getInvoiceNo() + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Status</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>Rejected</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Rejected By</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + approverName + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Date/Time</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + when + "</td></tr>"
                + (reason != null && !reason.isBlank() ? "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Reason</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + reason + "</td></tr>" : "")
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Customer</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + nvl(invoice.getCustomerName()) + "</td></tr>"
                + "<tr><td style='padding:8px;background:#f3f4f6;font-weight:600;'>Total Amount</td><td style='padding:8px;border-bottom:1px solid #e5e7eb;'>" + invoice.getTotalAmount() + "</td></tr>"
                + "</table>"
                + "<p style='color:#6b7280;font-size:13px;'>Please review the feedback, make the necessary corrections, and resubmit if required.</p>"
                + "<p style='color:#9ca3af;font-size:11px;margin-top:20px;'>SESOLA CRM — Automated Notification</p>"
                + "</div>";

            mailService.sendEmail(creator.getEmail().trim(), subject, body);
            log.info("Rejection mail sent to creator {} for invoice {}", creator.getEmail(), invoice.getInvoiceNo());
        } catch (Exception e) {
            log.error("Failed to send rejection mail for invoice {}: {}", invoice.getInvoiceNo(), e.getMessage());
        }
    }

    private String nvl(String s) { return s != null ? s : ""; }
    
    /**
     * Generate unique invoice number
     * Format: INV-YYYY-NNNN
     */
//    private synchronized String generateInvoiceNumber() {
//        int currentYear = Year.now().getValue();
//        String prefix = "INV-" + currentYear + "-";
//        
//        int maxAttempts = 20;
//        
//        for (int attempt = 0; attempt < maxAttempts; attempt++) {
//            try {
//                String lastInvoiceNo = invoiceRepository.findLastInvoiceNoByPrefix(prefix + "%");
//                
//                int nextNumber = 1;
//                
//                if (lastInvoiceNo != null && !lastInvoiceNo.isEmpty()) {
//                    log.info("Found last invoice: {}", lastInvoiceNo);
//                    
//                    String numericPart = lastInvoiceNo.replace(prefix, "");
//                    
//                    try {
//                        int lastNumber = Integer.parseInt(numericPart);
//                        nextNumber = lastNumber + 1;
//                        log.info("Last number was {}, next will be {}", lastNumber, nextNumber);
//                    } catch (NumberFormatException e) {
//                        log.error("Could not parse number from: {}. Starting from 1", lastInvoiceNo);
//                        nextNumber = 1;
//                    }
//                } else {
//                    log.info("No invoices found for year {}. Starting from 1", currentYear);
//                }
//                
//                String invoiceNo = prefix + String.format("%04d", nextNumber);
//                log.info("Attempting to use invoice number: {}", invoiceNo);
//                
//                Optional<InvoiceEntity> existing = invoiceRepository.findByInvoiceNoIncludingDeleted(invoiceNo);
//                
//                if (existing.isPresent()) {
//                    log.warn("Invoice {} already exists! Trying next number...", invoiceNo);
//                    
//                    nextNumber++;
//                    invoiceNo = prefix + String.format("%04d", nextNumber);
//                    
//                    existing = invoiceRepository.findByInvoiceNoIncludingDeleted(invoiceNo);
//                    
//                    if (existing.isEmpty()) {
//                        log.info("Using incremented invoice number: {}", invoiceNo);
//                        return invoiceNo;
//                    }
//                    
//                    Thread.sleep(50);
//                    continue;
//                }
//                
//                log.info("Invoice number {} is available and confirmed unique", invoiceNo);
//                return invoiceNo;
//                
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                throw new RuntimeException("Thread interrupted while generating invoice number");
//            } catch (Exception e) {
//                log.error("Error on attempt {} generating invoice number: {}", attempt + 1, e.getMessage());
//                
//                if (attempt >= maxAttempts - 1) {
//                    throw new RuntimeException("Failed to generate invoice number after " + maxAttempts + " attempts: " + e.getMessage());
//                }
//                
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException ie) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        }
//        
//        throw new RuntimeException("Failed to generate unique invoice number after " + maxAttempts + " attempts");
//    }
    
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