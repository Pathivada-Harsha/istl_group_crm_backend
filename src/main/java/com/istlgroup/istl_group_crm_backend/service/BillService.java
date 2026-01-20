package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.BillItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.BillPaymentEntity;
import com.istlgroup.istl_group_crm_backend.repo.BillItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.BillPaymentRepository;
import com.istlgroup.istl_group_crm_backend.repo.BillRepository;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillItemDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillStatsDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PaymentDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PaymentHistoryDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillService {
    
    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final BillPaymentRepository billPaymentRepository;
    private final VendorRepository vendorRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final UsersRepo usersRepo;
    
    private static final String UPLOAD_DIR = "uploads/bills/";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    
    /**
     * Get bills with role-based filtering
     */
    @Transactional(readOnly = true)
    public Page<BillDTO> getBills(
            String projectId,
            String groupId,
            String subGroupId,
            String status,
            Long vendorId,
            Long poId,
            String searchTerm,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            boolean isAdmin
    ) {
        Pageable pageable = PageRequest.of(
                page, 
                size, 
                Sort.Direction.fromString(sortDirection), 
                sortBy
        );
        
        Page<BillEntity> bills;
        
        // Role-based filtering
        if (isAdmin) {
            if (projectId != null && !projectId.isEmpty()) {
                bills = billRepository.findByProjectIdWithFilters(projectId, status, vendorId, poId, pageable);
            } else if (subGroupId != null && !subGroupId.isEmpty()) {
                bills = billRepository.findBySubGroupWithFilters(groupId, subGroupId, status, vendorId, poId, pageable);
            } else if (groupId != null && !groupId.isEmpty()) {
                bills = billRepository.findByGroupWithFilters(groupId, status, vendorId, poId, pageable);
            } else {
                bills = billRepository.findAllWithFilters(status, vendorId, poId, pageable);
            }
        } else {
            if (projectId == null || projectId.isEmpty()) {
                throw new RuntimeException("Project ID is required for non-admin users");
            }
            bills = billRepository.findByProjectIdWithFilters(projectId, status, vendorId, poId, pageable);
        }
        
        return bills.map(this::enrichBillEntity);
    }
    
    @Transactional(readOnly = true)
    public BillDTO getBillById(Long id) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + id));
        return enrichBillEntity(bill);
    }
    
    @Transactional
    public BillDTO createBill(BillDTO dto, Long userId) {
        log.info("Creating bill for vendor: {}, user: {}", dto.getVendorId(), userId);
        
        // Validate
        if (dto.getVendorId() == null) {
            throw new RuntimeException("Vendor ID is required");
        }
        
        // Check vendor exists
        vendorRepository.findById(dto.getVendorId())
                .orElseThrow(() -> new RuntimeException("Vendor not found: " + dto.getVendorId()));
        
        // Generate bill number if not provided
        if (dto.getBillNo() == null || dto.getBillNo().isEmpty()) {
            dto.setBillNo(generateBillNumber());
        }
        
        // Check if bill number already exists
        if (billRepository.existsByBillNo(dto.getBillNo())) {
            throw new RuntimeException("Bill number already exists: " + dto.getBillNo());
        }
        
        // Create bill entity using new operator (NO Builder)
        BillEntity bill = new BillEntity();
        bill.setBillNo(dto.getBillNo());
        bill.setVendorId(dto.getVendorId());
        bill.setPoId(dto.getPoId());
        bill.setBillDate(dto.getBillDate());
        bill.setDueDate(dto.getDueDate());
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setStatus("Pending");
        bill.setProjectId(dto.getProjectId());
        bill.setGroupId(dto.getGroupId());
        bill.setSubGroupId(dto.getSubGroupId());
        bill.setNotes(dto.getNotes());
        bill.setCreatedBy(userId);
        bill.setCreatedAt(LocalDateTime.now());
        bill.setUploadedBy(userId);
        bill.setUploadedOn(LocalDateTime.now());
        
        // IMPORTANT: Save bill FIRST to get ID
        bill = billRepository.save(bill);
        log.info("Bill saved with ID: {}", bill.getId());
        
        // Add items if provided
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            log.info("Adding {} items to bill", dto.getItems().size());
            
            for (BillItemDTO itemDTO : dto.getItems()) {
                BillItemEntity item = new BillItemEntity();
                item.setPoItemId(itemDTO.getPoItemId());
                item.setDescription(itemDTO.getDescription());
                item.setQuantity(itemDTO.getQuantity() != null ? itemDTO.getQuantity() : BigDecimal.ONE);
                item.setUnitPrice(itemDTO.getUnitPrice() != null ? itemDTO.getUnitPrice() : BigDecimal.ZERO);
                item.setTaxPercent(itemDTO.getTaxPercent() != null ? itemDTO.getTaxPercent() : BigDecimal.ZERO);
                
                // addItem method initializes list if null
                bill.addItem(item);
            }
        }
        
        // Calculate total amount
        recalculateBillTotal(bill);
        
        // Save again with items and total
        bill = billRepository.save(bill);
        
        log.info("Created bill: {} with total: {}", bill.getBillNo(), bill.getTotalAmount());
        
        return enrichBillEntity(bill);
    }
    
    @Transactional
    public BillDTO updateBill(Long id, BillDTO dto, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + id));
        
        if ("Paid".equals(bill.getStatus())) {
            throw new RuntimeException("Cannot edit paid bills");
        }
        
        if (dto.getBillDate() != null) bill.setBillDate(dto.getBillDate());
        if (dto.getDueDate() != null) bill.setDueDate(dto.getDueDate());
        if (dto.getNotes() != null) bill.setNotes(dto.getNotes());
        
        if (dto.getItems() != null) {
            bill.getItems().clear();
            for (BillItemDTO itemDTO : dto.getItems()) {
                BillItemEntity item = BillItemEntity.builder()
                        .bill(bill)
                        .poItemId(itemDTO.getPoItemId())
                        .description(itemDTO.getDescription())
                        .quantity(itemDTO.getQuantity())
                        .unitPrice(itemDTO.getUnitPrice())
                        .taxPercent(itemDTO.getTaxPercent())
                        .build();
                bill.addItem(item);
            }
            recalculateBillTotal(bill);
        }
        
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        bill = billRepository.save(bill);
        
        log.info("Updated bill: {} by user: {}", bill.getBillNo(), userId);
        return enrichBillEntity(bill);
    }
    
    @Transactional
    public void deleteBill(Long id, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + id));
        
        if ("Paid".equals(bill.getStatus())) {
            throw new RuntimeException("Cannot delete paid bills");
        }
        
        bill.setDeletedAt(LocalDateTime.now());
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        billRepository.save(bill);
        
        log.info("Deleted bill: {} by user: {}", bill.getBillNo(), userId);
    }
    
    @Transactional
    public String uploadBillFile(Long billId, MultipartFile file, Long userId) throws IOException {
        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }
        
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("File size exceeds 5MB limit");
        }
        
        String contentType = file.getContentType();
        if (!isValidFileType(contentType)) {
            throw new RuntimeException("Invalid file type. Only PDF, PNG, JPG, JPEG allowed");
        }
        
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));
        
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        
        Path filePath = uploadPath.resolve(uniqueFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        
        bill.setBillFilePath(filePath.toString());
        bill.setBillFileName(originalFilename);
        bill.setBillFileSize(file.getSize());
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        billRepository.save(bill);
        
        log.info("Uploaded file for bill: {} by user: {}", bill.getBillNo(), userId);
        return filePath.toString();
    }
    
    @Transactional
    public BillDTO addPayment(Long billId, PaymentDTO paymentDTO, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));
        
        BigDecimal paymentAmount = paymentDTO.getAmount();
        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Payment amount must be greater than zero");
        }
        
        BigDecimal balanceAmount = bill.getTotalAmount().subtract(bill.getPaidAmount());
        if (paymentAmount.compareTo(balanceAmount) > 0) {
            throw new RuntimeException("Payment amount exceeds balance: " + balanceAmount);
        }
        
        BillPaymentEntity payment = BillPaymentEntity.builder()
                .bill(bill)
                .paymentDate(paymentDTO.getPaymentDate())
                .paymentMode(paymentDTO.getPaymentMode())
                .referenceNumber(paymentDTO.getReferenceNumber())
                .amount(paymentAmount)
                .paidBy(userId)
                .createdAt(LocalDateTime.now())
                .notes(paymentDTO.getNotes())
                .build();
        
        bill.addPayment(payment);
        bill.setPaidAmount(bill.getPaidAmount().add(paymentAmount));
        bill.recalculateStatus();
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        bill = billRepository.save(bill);
        
        log.info("Added payment of {} to bill: {} by user: {}", paymentAmount, bill.getBillNo(), userId);
        return enrichBillEntity(bill);
    }
    
    @Transactional
    public BillDTO markAsPaid(Long billId, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));
        
        if ("Paid".equals(bill.getStatus())) {
            throw new RuntimeException("Bill is already paid");
        }
        
        BigDecimal remainingAmount = bill.getTotalAmount().subtract(bill.getPaidAmount());
        
        if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            BillPaymentEntity payment = BillPaymentEntity.builder()
                    .bill(bill)
                    .paymentDate(LocalDateTime.now())
                    .paymentMode("Manual Entry")
                    .referenceNumber("MANUAL-" + System.currentTimeMillis())
                    .amount(remainingAmount)
                    .paidBy(userId)
                    .createdAt(LocalDateTime.now())
                    .notes("Marked as fully paid")
                    .build();
            
            bill.addPayment(payment);
            bill.setPaidAmount(bill.getTotalAmount());
            bill.setStatus("Paid");
        }
        
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        bill = billRepository.save(bill);
        
        log.info("Marked bill as paid: {} by user: {}", bill.getBillNo(), userId);
        return enrichBillEntity(bill);
    }
    
    /**
     * FIXED: Get statistics using LocalDate instead of LocalDateTime
     */
    @Transactional(readOnly = true)
    public BillStatsDTO getStatistics(String projectId, String groupId, String subGroupId) {
        try {
            log.info("Calculating bill statistics for projectId: {}, groupId: {}, subGroupId: {}", 
                     projectId, groupId, subGroupId);
            
            long totalBills = billRepository.countBills(projectId, groupId, subGroupId);
            
            // Handle null outstanding amount
            BigDecimal outstandingAmount = billRepository.sumOutstandingAmount(projectId, groupId, subGroupId);
            if (outstandingAmount == null) {
                outstandingAmount = BigDecimal.ZERO;
            }
            
            // CRITICAL FIX: Use LocalDate instead of LocalDateTime
            YearMonth currentMonth = YearMonth.now();
            LocalDate startOfMonth = currentMonth.atDay(1);
            LocalDate endOfMonth = currentMonth.atEndOfMonth();
            
            log.debug("Counting bills between {} and {}", startOfMonth, endOfMonth);
            
            long billsThisMonth = billRepository.countBillsThisMonth(
                projectId, groupId, subGroupId, startOfMonth, endOfMonth
            );
            
            long paidBills = billRepository.countPaidBills(projectId, groupId, subGroupId);
            long linkedToPO = billRepository.countLinkedToPO(projectId, groupId, subGroupId);
            
            int linkedToPOPercentage = totalBills > 0 ? (int) Math.round((linkedToPO * 100.0) / totalBills) : 0;
            
            log.info("Bill stats calculated - total: {}, outstanding: {}, thisMonth: {}, paid: {}, linkedPO: {}%", 
                     totalBills, outstandingAmount, billsThisMonth, paidBills, linkedToPOPercentage);
            
            return BillStatsDTO.builder()
                    .totalBills(totalBills)
                    .outstandingAmount(outstandingAmount)
                    .billsThisMonth(billsThisMonth)
                    .paidBills(paidBills)
                    .linkedToPOPercentage(linkedToPOPercentage)
                    .build();
                    
        } catch (Exception e) {
            log.error("Error calculating bill statistics", e);
            // Return zeros on error instead of throwing
            return BillStatsDTO.builder()
                    .totalBills(0)
                    .outstandingAmount(BigDecimal.ZERO)
                    .billsThisMonth(0)
                    .paidBills(0)
                    .linkedToPOPercentage(0)
                    .build();
        }
    }
    
    // ========== HELPER METHODS ==========
    
    private BillDTO enrichBillEntity(BillEntity bill) {
        BillDTO dto = new BillDTO();
        dto.setId(bill.getId());
        dto.setBillNo(bill.getBillNo());
        dto.setVendorId(bill.getVendorId());
        dto.setPoId(bill.getPoId());
        dto.setBillDate(bill.getBillDate());
        dto.setDueDate(bill.getDueDate());
        dto.setTotalAmount(bill.getTotalAmount());
        dto.setPaidAmount(bill.getPaidAmount());
        dto.setBalanceAmount(bill.getBalanceAmount());
        dto.setStatus(bill.getStatus());
        dto.setProjectId(bill.getProjectId());
        dto.setGroupId(bill.getGroupId());
        dto.setSubGroupId(bill.getSubGroupId());
        dto.setNotes(bill.getNotes());
        dto.setBillFilePath(bill.getBillFilePath());
        dto.setBillFileName(bill.getBillFileName());
        dto.setBillFileSize(bill.getBillFileSize());
        dto.setUploadedOn(bill.getUploadedOn());
        dto.setCreatedAt(bill.getCreatedAt());
        dto.setUpdatedAt(bill.getUpdatedAt());
        
        // Get vendor name
        vendorRepository.findById(bill.getVendorId()).ifPresent(vendor -> {
            dto.setVendorName(vendor.getName());
        });
        
        // Get PO number
        if (bill.getPoId() != null) {
            purchaseOrderRepository.findById(bill.getPoId()).ifPresent(po -> {
                dto.setPoNumber(po.getPoNo());
                dto.setQuotationId(po.getQuotationId() != null ? po.getQuotationId().toString() : null);
            });
        }
        
        // Get uploader name
        if (bill.getUploadedBy() != null) {
            usersRepo.findById(bill.getUploadedBy()).ifPresent(user -> {
                dto.setUploadedByName(user.getName());
            });
        }
        
        // Get items
        List<BillItemDTO> items = bill.getItems().stream()
                .map(item -> {
                    BillItemDTO itemDTO = new BillItemDTO();
                    itemDTO.setId(item.getId());
                    itemDTO.setPoItemId(item.getPoItemId());
                    itemDTO.setDescription(item.getDescription());
                    itemDTO.setQuantity(item.getQuantity());
                    itemDTO.setUnitPrice(item.getUnitPrice());
                    itemDTO.setTaxPercent(item.getTaxPercent());
                    itemDTO.setLineTotal(item.getLineTotal());
                    return itemDTO;
                })
                .collect(Collectors.toList());
        dto.setItems(items);
        
        // Get payment history
        List<PaymentHistoryDTO> payments = bill.getPayments().stream()
                .map(payment -> {
                    PaymentHistoryDTO paymentDTO = new PaymentHistoryDTO();
                    paymentDTO.setId(payment.getId());
                    paymentDTO.setPaymentDate(payment.getPaymentDate());
                    paymentDTO.setPaymentMode(payment.getPaymentMode());
                    paymentDTO.setReferenceNumber(payment.getReferenceNumber());
                    paymentDTO.setAmount(payment.getAmount());
                    
                    if (payment.getPaidBy() != null) {
                        usersRepo.findById(payment.getPaidBy()).ifPresent(user -> {
                            paymentDTO.setPaidByName(user.getName());
                        });
                    }
                    
                    return paymentDTO;
                })
                .collect(Collectors.toList());
        dto.setPaymentHistory(payments);
        
        return dto;
    }
    
    private void recalculateBillTotal(BillEntity bill) {
        BigDecimal subtotal = bill.getItems().stream()
                .map(item -> item.getQuantity().multiply(item.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal taxAmount = bill.getItems().stream()
                .map(BillItemEntity::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        bill.setTotalAmount(subtotal.add(taxAmount));
    }
    
    private String generateBillNumber() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        String prefix = "BILL-" + year + "-";
        String maxBillNo = billRepository.findMaxBillNoWithPrefix(prefix + "%");
        
        int nextNumber = 1;
        if (maxBillNo != null) {
            String numberPart = maxBillNo.substring(maxBillNo.lastIndexOf("-") + 1);
            nextNumber = Integer.parseInt(numberPart) + 1;
        }
        
        return prefix + String.format("%03d", nextNumber);
    }
    
    private boolean isValidFileType(String contentType) {
        return contentType != null && (
                contentType.equals("application/pdf") ||
                contentType.equals("image/png") ||
                contentType.equals("image/jpeg") ||
                contentType.equals("image/jpg")
        );
    }
}