package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.BillPaymentEntity;
import com.istlgroup.istl_group_crm_backend.entity.VendorAdvanceAllocationEntity;
import com.istlgroup.istl_group_crm_backend.entity.VendorAdvanceEntity;
import com.istlgroup.istl_group_crm_backend.repo.BillPaymentRepository;
import com.istlgroup.istl_group_crm_backend.repo.BillRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorAdvanceAllocationRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorAdvanceRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;
import com.istlgroup.istl_group_crm_backend.service.ProjectStatsService;

@Service
@RequiredArgsConstructor
@Slf4j
public class VendorAdvanceService {

    private final VendorAdvanceRepository advanceRepository;
    private final VendorAdvanceAllocationRepository allocationRepository;
    private final BillRepository billRepository;
    private final BillPaymentRepository billPaymentRepository;
    private final VendorRepository vendorRepository;
    private final ProjectRepository projectRepository;
    private final ProjectStatsService projectStatsService;

    // ─────────────────────────────────────────────────────────────────────────
    // LIST / GET
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<VendorAdvanceEntity> getAdvances(
            String groupId, String subGroupId, String projectId,
            String paymentType, Long vendorId, String searchTerm,
            String paymentDateFromStr, String paymentDateToStr,
            int page, int size, String sortBy, String sortDirection) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy));

        String cleanProject     = (projectId   != null && !projectId.trim().isEmpty())   ? projectId.trim()   : null;
        String cleanGroup       = (groupId     != null && !groupId.trim().isEmpty())     ? groupId.trim()     : null;
        String cleanSub         = (subGroupId  != null && !subGroupId.trim().isEmpty())  ? subGroupId.trim()  : null;
        String cleanPaymentType = (paymentType != null && !paymentType.trim().isEmpty() && !"all".equalsIgnoreCase(paymentType.trim())) ? paymentType.trim() : null;
        String cleanSearch      = (searchTerm  != null && !searchTerm.trim().isEmpty())  ? searchTerm.trim()  : null;

        java.time.LocalDate fromDate = null, toDate = null;
        if (paymentDateFromStr != null && !paymentDateFromStr.isBlank()) {
            try { fromDate = java.time.LocalDate.parse(paymentDateFromStr); } catch (Exception ignored) {}
        }
        if (paymentDateToStr != null && !paymentDateToStr.isBlank()) {
            try { toDate = java.time.LocalDate.parse(paymentDateToStr); } catch (Exception ignored) {}
        }

        return advanceRepository.findAllWithFilters(
                cleanProject, cleanGroup, cleanSub, vendorId, cleanPaymentType, cleanSearch, fromDate, toDate, pageable);
    }

    @Transactional(readOnly = true)
    public VendorAdvanceEntity getById(Long id) {
        return advanceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vendor advance not found: " + id));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public VendorAdvanceEntity createAdvance(VendorAdvanceEntity advance, Long userId) {
        vendorRepository.findById(advance.getVendorId())
                .orElseThrow(() -> new RuntimeException("Vendor not found"));

        // Advance/payment number is derived from the DB-generated id (see below).
        // Use a temp placeholder to satisfy NOT NULL constraint.
        advance.setAdvanceNo("__TEMP_VADV_" + System.nanoTime() + "__");
        advance.setCreatedBy(userId);
        advance.setAppliedAmount(BigDecimal.ZERO);

        if (VendorAdvanceEntity.PaymentType.BILL_PAYMENT.equals(advance.getPaymentType())) {
            if (advance.getBillId() == null)
                throw new RuntimeException("Bill ID required for BILL_PAYMENT type");

            BillEntity bill = billRepository.findByIdAndNotDeleted(advance.getBillId())
                    .orElseThrow(() -> new RuntimeException("Bill not found"));

            if (advance.getAmount().compareTo(bill.getBalanceAmount()) > 0)
                throw new RuntimeException("Payment amount exceeds bill balance of " + bill.getBalanceAmount());

            advance.setAppliedAmount(advance.getAmount());
        }

        // First save — DB assigns auto-increment id
        VendorAdvanceEntity saved = advanceRepository.save(advance);
        // Derive advance/payment number from the DB id — only if no real code exists yet.
        String existingAdvNo = saved.getAdvanceNo();
        if (existingAdvNo == null || existingAdvNo.isBlank() || existingAdvNo.startsWith("__TEMP_")) {
            boolean isAdvance = VendorAdvanceEntity.PaymentType.ADVANCE.equals(saved.getPaymentType());
            String prefix = isAdvance ? "VADV" : "VPAY";
            saved.setAdvanceNo(String.format("%s-%d-%04d", prefix,
                java.time.Year.now().getValue(), saved.getId()));
            saved = advanceRepository.save(saved);
        }

        if (VendorAdvanceEntity.PaymentType.BILL_PAYMENT.equals(saved.getPaymentType())) {
            applyAmountToBill(saved.getBillId(), saved.getAmount(), userId,
                    saved.getPaymentMode(), saved.getTransactionReference(),
                    "Payment via advance " + saved.getAdvanceNo(), true);
        }

        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            projectStatsService.updateProjectAfterBillPayment(saved.getProjectId());
            log.info("Synced project [{}] paid_bill_value after vendor advance [{}] recorded",
                     saved.getProjectId(), saved.getAdvanceNo());
        }

        log.info("Created vendor advance: {}", saved.getAdvanceNo());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ALLOCATE ADVANCE → BILLS
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public VendorAdvanceEntity allocateAdvanceToBills(
            Long advanceId, List<Map<String, Object>> allocations, Long userId) {

        VendorAdvanceEntity advance = getById(advanceId);
        if (!VendorAdvanceEntity.PaymentType.ADVANCE.equals(advance.getPaymentType()))
            throw new RuntimeException("Only ADVANCE payments can be allocated");

        BigDecimal totalNew = BigDecimal.ZERO;
        for (Map<String, Object> alloc : allocations) {
            Long billId = Long.valueOf(alloc.get("billId").toString());
            BigDecimal amount = new BigDecimal(alloc.get("amount").toString());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;

            BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                    .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));
            if (amount.compareTo(bill.getBalanceAmount()) > 0)
                throw new RuntimeException("Allocation exceeds bill balance for " + bill.getBillNo());

            VendorAdvanceAllocationEntity entity = VendorAdvanceAllocationEntity.builder()
                    .advance(advance)
                    .billId(billId)
                    .allocatedAmount(amount)
                    .allocationDate(LocalDateTime.now())
                    .allocatedBy(userId)
                    .build();
            allocationRepository.save(entity);

            applyAmountToBill(billId, amount, userId,
                    advance.getPaymentMode(), advance.getTransactionReference(),
                    "Advance allocation from " + advance.getAdvanceNo(), false);

            totalNew = totalNew.add(amount);
        }

        BigDecimal newApplied = advance.getAppliedAmount().add(totalNew);
        if (newApplied.compareTo(advance.getAmount()) > 0)
            throw new RuntimeException("Total allocation exceeds available advance");

        advance.setAppliedAmount(newApplied);
        advance.setUpdatedAt(LocalDateTime.now());
        return advanceRepository.save(advance);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET ALLOCATION DETAILS (for View modal)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllocationDetails(Long advanceId) {
        List<VendorAdvanceAllocationEntity> allocations =
                allocationRepository.findByAdvance_IdOrderByAllocationDateDesc(advanceId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (VendorAdvanceAllocationEntity a : allocations) {
            BillEntity bill = billRepository.findByIdAndNotDeleted(a.getBillId()).orElse(null);
            if (bill == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("allocationId",     a.getId());
            m.put("billId",           bill.getId());
            m.put("billNo",           bill.getBillNo());
            m.put("allocatedAmount",  a.getAllocatedAmount());
            m.put("allocationDate",   a.getAllocationDate());
            m.put("billTotalAmount",  bill.getTotalAmount());
            m.put("billBalance",      bill.getBalanceAmount());
            m.put("billStatus",       bill.getStatus());
            result.add(m);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REMOVE ALLOCATION
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public VendorAdvanceEntity removeAllocation(Long advanceId, Long billId, Long userId) {
        VendorAdvanceEntity advance = getById(advanceId);
        VendorAdvanceAllocationEntity alloc = allocationRepository
                .findByAdvance_IdAndBillId(advanceId, billId)
                .orElseThrow(() -> new RuntimeException("Allocation not found"));

        BigDecimal amount = alloc.getAllocatedAmount();
        reverseAmountFromBill(billId, amount, advance.getAdvanceNo());
        allocationRepository.delete(alloc);

        advance.setAppliedAmount(advance.getAppliedAmount().subtract(amount).max(BigDecimal.ZERO));
        advance.setUpdatedAt(LocalDateTime.now());
        return advanceRepository.save(advance);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public VendorAdvanceEntity updateAdvance(Long id, VendorAdvanceEntity updated, Long userId) {
        VendorAdvanceEntity existing = getById(id);

        boolean amountChanged = existing.getAmount().compareTo(updated.getAmount()) != 0;
        boolean isBillPayment = VendorAdvanceEntity.PaymentType.BILL_PAYMENT.equals(existing.getPaymentType());
        boolean isAdvance     = VendorAdvanceEntity.PaymentType.ADVANCE.equals(existing.getPaymentType());

        // ── ADVANCE: block reducing below already-allocated amount ────────────
        if (isAdvance && amountChanged && existing.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            if (updated.getAmount().compareTo(existing.getAppliedAmount()) < 0) {
                throw new RuntimeException(
                    "Cannot reduce advance amount below already-allocated amount of "
                    + existing.getAppliedAmount());
            }
        }

        // ── BILL_PAYMENT: reverse old amount, validate, apply new amount ──────
        if (isBillPayment && existing.getBillId() != null && amountChanged) {
            BigDecimal newAmount = updated.getAmount();
            if (newAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Payment amount must be greater than zero");
            }

            BillEntity bill = billRepository.findByIdAndNotDeleted(existing.getBillId())
                    .orElseThrow(() -> new RuntimeException("Bill not found"));

            BigDecimal oldAmount         = existing.getAmount();
            BigDecimal paidAfterReversal = bill.getPaidAmount().subtract(oldAmount).max(BigDecimal.ZERO);
            BigDecimal restoredBalance   = bill.getTotalAmount().subtract(paidAfterReversal);
            if (restoredBalance.compareTo(BigDecimal.ZERO) < 0) restoredBalance = BigDecimal.ZERO;

            if (newAmount.compareTo(restoredBalance) > 0) {
                throw new RuntimeException(
                    "Updated amount " + newAmount + " exceeds available bill balance "
                    + restoredBalance);
            }

            reverseAmountFromBill(existing.getBillId(), oldAmount, existing.getAdvanceNo());
            applyAmountToBill(existing.getBillId(), newAmount, userId,
                    updated.getPaymentMode() != null ? updated.getPaymentMode() : existing.getPaymentMode(),
                    updated.getTransactionReference() != null ? updated.getTransactionReference() : existing.getTransactionReference(),
                    "Corrected payment via " + existing.getAdvanceNo(), true);

            existing.setAppliedAmount(newAmount);
            log.info("Updated BILL_PAYMENT {} amount: {} → {} on bill id {}",
                     existing.getAdvanceNo(), oldAmount, newAmount, existing.getBillId());
        }

        // ── ADVANCE: handle vendor change — reverse all allocations ──────────
        // vendorId change is only allowed for ADVANCE type. BILL_PAYMENT vendor is locked.
        Long oldVendorId = existing.getVendorId();
        Long newVendorId = (updated.getVendorId() != null) ? updated.getVendorId() : oldVendorId;
        boolean vendorChanged = isAdvance && !newVendorId.equals(oldVendorId);

        if (vendorChanged) {
            // Validate the new vendor exists
            vendorRepository.findById(newVendorId)
                    .orElseThrow(() -> new RuntimeException("New vendor not found: " + newVendorId));
            // Reverse all existing bill allocations — they belong to the old vendor
            reverseAllAllocationsForAdvance(existing);
            existing.setVendorId(newVendorId);
            log.info("Advance [{}] vendor reassigned from {} to {}",
                     existing.getAdvanceNo(), oldVendorId, newVendorId);
        }

        // ── Capture old project before any changes ────────────────────────────
        String oldProjectId = existing.getProjectId();
        String newProjectId = updated.getProjectId() != null
                ? (updated.getProjectId().isBlank() ? null : updated.getProjectId())
                : existing.getProjectId();
        boolean projectChanged = isAdvance
                && newProjectId != null
                && !newProjectId.equals(oldProjectId);

        // ── ADVANCE: if project changes, reverse all bill allocations first ───
        // (skip if vendorChanged already did the reversal above)
        if (projectChanged && !vendorChanged && existing.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            reverseAllAllocationsForAdvance(existing);
            log.info("Cleared all allocations for advance [{}] before project reassignment {} → {}",
                     existing.getAdvanceNo(), oldProjectId, newProjectId);
        }

        existing.setAdvanceDate(updated.getAdvanceDate());
        existing.setAmount(updated.getAmount());
        existing.setPaymentMode(updated.getPaymentMode());
        existing.setTransactionReference(updated.getTransactionReference());
        existing.setNotes(updated.getNotes());
        existing.setCompany(updated.getCompany());

        // ── Update project/group assignment if provided (ADVANCE only) ────────
        if (isAdvance) {
            if (updated.getProjectId() != null) {
                existing.setProjectId(updated.getProjectId().isBlank() ? null : updated.getProjectId());
            }
            if (updated.getGroupId() != null) {
                existing.setGroupId(updated.getGroupId().isBlank() ? null : updated.getGroupId());
            }
            if (updated.getSubGroupId() != null) {
                existing.setSubGroupId(updated.getSubGroupId().isBlank() ? null : updated.getSubGroupId());
            }
        }

        existing.setUpdatedAt(LocalDateTime.now());
        VendorAdvanceEntity saved = advanceRepository.save(existing);

        // ── Sync new project stats ────────────────────────────────────────────
        if (saved.getProjectId() != null && !saved.getProjectId().isBlank()) {
            projectStatsService.updateProjectAfterBillPayment(saved.getProjectId());
            log.info("Synced project [{}] paid_bill_value after advance [{}] updated",
                     saved.getProjectId(), saved.getAdvanceNo());
        }
        // ── Sync old project stats if project changed ─────────────────────────
        if (oldProjectId != null && !oldProjectId.isBlank()
                && !oldProjectId.equals(saved.getProjectId())) {
            projectStatsService.updateProjectAfterBillPayment(oldProjectId);
            log.info("Synced old project [{}] paid_bill_value after advance [{}] moved away",
                     oldProjectId, saved.getAdvanceNo());
        }
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE (soft)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteAdvance(Long id, Long userId) {
        VendorAdvanceEntity advance = getById(id);

        if (VendorAdvanceEntity.PaymentType.ADVANCE.equals(advance.getPaymentType())
                && advance.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            List<VendorAdvanceAllocationEntity> allocs =
                    allocationRepository.findByAdvance_IdOrderByAllocationDateDesc(advance.getId());
            for (VendorAdvanceAllocationEntity a : allocs) {
                reverseAmountFromBill(a.getBillId(), a.getAllocatedAmount(), advance.getAdvanceNo());
            }
            allocationRepository.deleteAll(allocs);
        }

        if (VendorAdvanceEntity.PaymentType.BILL_PAYMENT.equals(advance.getPaymentType())
                && advance.getBillId() != null) {
            reverseAmountFromBill(advance.getBillId(), advance.getAmount(), advance.getAdvanceNo());
        }

        advance.setDeletedAt(LocalDateTime.now());
        advanceRepository.save(advance);
        log.info("Soft-deleted vendor advance: {}", advance.getAdvanceNo());

        if (advance.getProjectId() != null && !advance.getProjectId().isBlank()) {
            projectStatsService.updateProjectAfterBillPayment(advance.getProjectId());
            log.info("Synced project [{}] paid_bill_value after advance [{}] deleted",
                     advance.getProjectId(), advance.getAdvanceNo());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESTORE
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public VendorAdvanceEntity restoreAdvance(Long id) {
        VendorAdvanceEntity advance = advanceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Advance not found"));
        if (advance.getDeletedAt() == null)
            throw new RuntimeException("Advance is not deleted");
        advance.setDeletedAt(null);
        advance.setUpdatedAt(LocalDateTime.now());
        return advanceRepository.save(advance);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SUMMARY STATS
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(String groupId, String subGroupId, String projectId) {
        return getSummary(groupId, subGroupId, projectId, null, null, null, null);
    }

    public Map<String, Object> getSummary(String groupId, String subGroupId, String projectId,
                                           String paymentTypeFilter, String searchTerm) {
        return getSummary(groupId, subGroupId, projectId, paymentTypeFilter, searchTerm, null, null);
    }

    public Map<String, Object> getSummary(String groupId, String subGroupId, String projectId,
                                           String paymentTypeFilter, String searchTerm,
                                           String paymentDateFromStr, String paymentDateToStr) {
        String ptFilter = (paymentTypeFilter != null && !paymentTypeFilter.trim().isEmpty() && !"all".equalsIgnoreCase(paymentTypeFilter.trim())) ? paymentTypeFilter.trim() : null;
        String search   = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;

        java.time.LocalDate fromDate = null, toDate = null;
        if (paymentDateFromStr != null && !paymentDateFromStr.isBlank()) {
            try { fromDate = java.time.LocalDate.parse(paymentDateFromStr); } catch (Exception ignored) {}
        }
        if (paymentDateToStr != null && !paymentDateToStr.isBlank()) {
            try { toDate = java.time.LocalDate.parse(paymentDateToStr); } catch (Exception ignored) {}
        }

        long total          = advanceRepository.countFilteredWithOptions(projectId, groupId, subGroupId, ptFilter, search, fromDate, toDate);
        BigDecimal totalAmt = advanceRepository.sumTotalAmountWithOptions(projectId, groupId, subGroupId, ptFilter, search, fromDate, toDate);
        BigDecimal appliedAmt = advanceRepository.sumAppliedAmountWithOptions(projectId, groupId, subGroupId, ptFilter, search, fromDate, toDate);

        Map<String, Object> r = new HashMap<>();
        r.put("totalAdvances",    total);
        r.put("totalAmount",      totalAmt   != null ? totalAmt   : BigDecimal.ZERO);
        r.put("appliedAmount",    appliedAmt != null ? appliedAmt : BigDecimal.ZERO);
        r.put("unappliedAmount",  totalAmt   != null && appliedAmt != null
                                  ? totalAmt.subtract(appliedAmt).max(BigDecimal.ZERO) : BigDecimal.ZERO);
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UNAPPLIED ADVANCES FOR A VENDOR
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUnappliedAdvancesForVendor(Long vendorId) {
        List<VendorAdvanceEntity> list = advanceRepository.findUnappliedAdvancesByVendor(vendorId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (VendorAdvanceEntity a : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("id",                  a.getId());
            m.put("advanceNo",           a.getAdvanceNo());
            m.put("advanceDate",         a.getAdvanceDate());
            m.put("amount",              a.getAmount());
            m.put("appliedAmount",       a.getAppliedAmount());
            m.put("unappliedAmount",     a.getUnappliedAmount());
            m.put("paymentMode",         a.getPaymentMode());
            m.put("transactionReference",a.getTransactionReference());
            result.add(m);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYABLE BILLS FOR VENDOR + PROJECT
    // Dedicated to the Vendor Payments modal — never used by Bills Received page.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns Pending + Partially-Paid bills for the given vendor.
     * When projectId is provided, results are scoped to that project only.
     * Used exclusively by: "Payment Against Bill" picker + "Allocate Advance" modal.
     */
    @Transactional(readOnly = true)
    public List<BillEntity> getPayableBillsForVendor(Long vendorId, String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            return billRepository.findPayableBillsForVendorAndProject(vendorId, projectId);
        }
        return billRepository.findPayableBillsForVendor(vendorId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETED LIST
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VendorAdvanceEntity> getDeletedAdvances() {
        return advanceRepository.findDeleted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ALLOCATIONS LINKED TO A BILL (for bill view modal)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllocationsForBill(Long billId) {
        List<VendorAdvanceAllocationEntity> allocations =
                allocationRepository.findByBillIdOrderByAllocationDateDesc(billId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (VendorAdvanceAllocationEntity a : allocations) {
            VendorAdvanceEntity advance = a.getAdvance();
            Map<String, Object> m = new HashMap<>();
            m.put("allocationId",        a.getId());
            m.put("advanceId",           advance.getId());
            m.put("advanceNo",           advance.getAdvanceNo());
            m.put("advanceDate",         advance.getAdvanceDate());
            m.put("allocatedAmount",     a.getAllocatedAmount());
            m.put("allocationDate",      a.getAllocationDate());
            m.put("paymentMode",         advance.getPaymentMode());
            m.put("transactionReference",advance.getTransactionReference());
            result.add(m);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reverses ALL existing bill allocations for an advance and resets
     * its appliedAmount to zero. Called when vendor or project changes on edit.
     */
    private void reverseAllAllocationsForAdvance(VendorAdvanceEntity advance) {
        if (advance.getAppliedAmount().compareTo(BigDecimal.ZERO) > 0) {
            List<VendorAdvanceAllocationEntity> allocs =
                    allocationRepository.findByAdvance_IdOrderByAllocationDateDesc(advance.getId());
            for (VendorAdvanceAllocationEntity a : allocs) {
                reverseAmountFromBill(a.getBillId(), a.getAllocatedAmount(), advance.getAdvanceNo());
                log.info("Reversed allocation of {} from bill id {} due to reassignment of advance {}",
                         a.getAllocatedAmount(), a.getBillId(), advance.getAdvanceNo());
            }
            allocationRepository.deleteAll(allocs);
            advance.setAppliedAmount(BigDecimal.ZERO);
        }
    }

    private void applyAmountToBill(Long billId, BigDecimal amount, Long userId,
                                   String paymentMode, String reference, String notes,
                                   boolean updateProjectStats) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));

        BigDecimal newPaid = bill.getPaidAmount().add(amount);
        bill.setPaidAmount(newPaid);
        bill.recalculateStatus();
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        billRepository.save(bill);

        String advanceRef = null;
        if (notes != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\b(VADV|VPAY)-\\d{4}-\\d{4}\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(notes);
            if (m.find()) advanceRef = m.group().toUpperCase();
        }

        BillPaymentEntity payment = BillPaymentEntity.builder()
                .bill(bill)
                .paymentDate(LocalDateTime.now())
                .paymentMode(paymentMode != null ? paymentMode : "—")
                .referenceNumber(advanceRef != null ? advanceRef : reference)
                .amount(amount)
                .paidBy(userId)
                .createdAt(LocalDateTime.now())
                .notes(notes)
                .build();
        billPaymentRepository.save(payment);

        log.info("Applied {} to bill {} (new paid: {})", amount, bill.getBillNo(), newPaid);
    }

    private void reverseAmountFromBill(Long billId, BigDecimal amount) {
        reverseAmountFromBill(billId, amount, null);
    }

    private void reverseAmountFromBill(Long billId, BigDecimal amount, String advanceNo) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId).orElse(null);
        if (bill == null) return;

        boolean wasPaid = "Paid".equals(bill.getStatus());

        BigDecimal newPaid = bill.getPaidAmount().subtract(amount).max(BigDecimal.ZERO);
        bill.setPaidAmount(newPaid);
        bill.recalculateStatus();
        bill.setUpdatedAt(LocalDateTime.now());
        billRepository.save(bill);

        if (bill.getProjectId() != null && !bill.getProjectId().isBlank()) {
            try {
                int paidDelta = (wasPaid && !"Paid".equals(bill.getStatus())) ? 1 : 0;
                projectRepository.decrementProjectPaidBillValue(
                        bill.getProjectId(), amount, paidDelta);
                log.info("Reversed project [{}] paid_bill_value -{} via advance reversal",
                         bill.getProjectId(), amount);
            } catch (Exception e) {
                log.error("Failed to reverse project bill stats for [{}]: {}",
                          bill.getProjectId(), e.getMessage(), e);
            }
        }

        if (advanceNo != null && !advanceNo.isEmpty()) {
            billPaymentRepository.deleteByReferenceNumber(advanceNo);
            log.info("Deleted bill_payment history rows for advance: {}", advanceNo);
        }

        log.info("Reversed {} from bill {} (new paid: {})", amount, bill.getBillNo(), newPaid);
    }

    private synchronized String generateAdvanceNumber() {
        int year = Year.now().getValue();
        String prefix = "VADV-" + year + "-";
        return generateNumberWithPrefix(prefix);
    }

    private synchronized String generatePaymentNumber() {
        int year = Year.now().getValue();
        String prefix = "VPAY-" + year + "-";
        return generateNumberWithPrefix(prefix);
    }

    private String generateNumberWithPrefix(String prefix) {
        String last = advanceRepository.findLastAdvanceNoByPrefix(prefix + "%", prefix);
        int next = 1;
        if (last != null && !last.isEmpty()) {
            try {
                next = Integer.parseInt(last.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                log.warn("Could not parse number from advance no: {}", last);
            }
        }
        return prefix + String.format("%04d", next);
    }
}