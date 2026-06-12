package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvPaymentWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvPaymentService {

    private final InvPaymentRepository  paymentRepository;
    private final InvBillRepository     billRepository;
    private final WarehouseRepository   warehouseRepository;

    // ── List ──────────────────────────────────────────────────────────────────

    public Map<String, Object> list(String groupName, String subGroupName,
                                    String projectId, Long vendorId,
                                    String paymentType, String paymentMode,
                                    String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        // ADVANCE -> isAdvance=true (billId IS NULL)
        // BILL_PAYMENT -> isAdvance=false (billId IS NOT NULL)
        // null/blank -> isAdvance=null (no filter)
        Boolean isAdvance = null;
        String typeClean = nullIfBlank(paymentType);
        if ("ADVANCE".equalsIgnoreCase(typeClean))      isAdvance = Boolean.TRUE;
        else if ("BILL_PAYMENT".equalsIgnoreCase(typeClean)) isAdvance = Boolean.FALSE;

        Page<InvPaymentEntity> result = paymentRepository.findFiltered(
            nullIfBlank(groupName), nullIfBlank(subGroupName),
            nullIfBlank(projectId), vendorId,
            nullIfBlank(paymentMode), isAdvance,
            nullIfBlank(search),
            pageable
        );

        Map<Long, String> billNos   = buildBillNoMap(result.getContent());
        Map<String, String> whNames = buildWarehouseNameMap(result.getContent().stream()
            .map(InvPaymentEntity::getWarehouseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<InvPaymentWrapper> content = result.getContent().stream()
            .map(e -> InvPaymentWrapper.from(e, billNos.get(e.getBillId()), whNames.get(String.valueOf(e.getWarehouseId()))))
            .collect(Collectors.toList());

        return Map.of(
            "content", content,
            "totalElements", result.getTotalElements(),
            "totalPages",    result.getTotalPages(),
            "size",          result.getSize(),
            "number",        result.getNumber()
        );
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Records a payment against an inventory bill.
     * Updates the bill's paid_amount and recalculates its status.
     * If the payment is for a bill, scope fields (group/sub/project/warehouse)
     * are inherited from the bill when not explicitly provided.
     */
    @Transactional
    public InvPaymentWrapper create(InvPaymentWrapper req, Long createdBy) {
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Payment amount must be positive");
        if (req.getVendorName() == null || req.getVendorName().isBlank())
            throw new IllegalArgumentException("Vendor name is required");

        // Resolve bill and inherit scope
        InvBillEntity bill = null;
        if (req.getBillId() != null) {
            bill = billRepository.findById(req.getBillId())
                .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + req.getBillId()));

            // Validate amount doesn't exceed balance
            BigDecimal balance = bill.getBalanceAmount();
            if (req.getAmount().compareTo(balance) > 0) {
                throw new IllegalArgumentException(
                    String.format("Payment ₹%.2f exceeds bill balance ₹%.2f", req.getAmount(), balance));
            }
        }

        InvPaymentEntity entity = InvPaymentEntity.builder()
            .paymentNo("PENDING")
            .billId(req.getBillId())
            .vendorId(req.getVendorId() != null ? req.getVendorId()
                : (bill != null ? bill.getVendorId() : null))
            .vendorName(req.getVendorName().trim())
            .warehouseId(req.getWarehouseId() != null ? req.getWarehouseId()
                : (bill != null ? bill.getWarehouseId() : null))
            .groupName(nullIfBlank(req.getGroupName()) != null ? req.getGroupName()
                : (bill != null ? bill.getGroupName() : null))
            .subGroupName(nullIfBlank(req.getSubGroupName()) != null ? req.getSubGroupName()
                : (bill != null ? bill.getSubGroupName() : null))
            .projectId(nullIfBlank(req.getProjectId()) != null ? req.getProjectId()
                : (bill != null ? bill.getProjectId() : null))
            .paymentDate(req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now())
            .amount(req.getAmount())
            .paymentMode(req.getPaymentMode())
            .referenceNumber(req.getReferenceNumber())
            .notes(req.getNotes())
            .createdBy(createdBy)
            .build();

        InvPaymentEntity saved = paymentRepository.saveAndFlush(entity);
        String payNo = String.format("INV-PAY-%d-%05d", LocalDate.now().getYear(), saved.getId());
        saved.setPaymentNo(payNo);
        saved = paymentRepository.saveAndFlush(saved);

        // Update bill's paid_amount and status
        if (bill != null) {
            BigDecimal totalPaid = paymentRepository.sumAmountByBillId(bill.getId());
            bill.setPaidAmount(totalPaid);
            bill.recalculateStatus();
            billRepository.save(bill);
            log.info("Payment {} of ₹{} applied to bill {} — new status: {}",
                     payNo, req.getAmount(), bill.getBillNo(), bill.getStatus());
        }

        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        String billNo = bill != null ? bill.getBillNo() : null;
        return InvPaymentWrapper.from(saved, billNo, whName);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Updates non-structural fields on a payment: date, amount, mode, reference, notes.
     * If the payment is linked to a bill and the amount changes, the bill's paidAmount
     * is recalculated to keep it accurate.
     */
    @Transactional
    public InvPaymentWrapper update(Long id, InvPaymentWrapper req, Long updatedBy) {
        InvPaymentEntity pay = paymentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));

        if (req.getPaymentDate()     != null) pay.setPaymentDate(req.getPaymentDate());
        if (req.getPaymentMode()     != null) pay.setPaymentMode(req.getPaymentMode());
        if (req.getReferenceNumber() != null) pay.setReferenceNumber(req.getReferenceNumber());
        if (req.getNotes()           != null) pay.setNotes(req.getNotes());
        if (nullIfBlank(req.getVendorName()) != null) pay.setVendorName(req.getVendorName().trim());
        if (req.getGroupName()       != null) pay.setGroupName(req.getGroupName());
        if (req.getSubGroupName()    != null) pay.setSubGroupName(req.getSubGroupName());
        if (req.getWarehouseId()     != null) pay.setWarehouseId(req.getWarehouseId());

        // Amount change — validate against bill balance
        if (req.getAmount() != null && req.getAmount().compareTo(BigDecimal.ZERO) > 0
                && req.getAmount().compareTo(pay.getAmount()) != 0) {
            if (pay.getBillId() != null) {
                InvBillEntity bill = billRepository.findById(pay.getBillId())
                    .orElseThrow(() -> new IllegalArgumentException("Linked bill not found"));
                // available = bill total - other payments (excluding this one)
                BigDecimal otherPaid = paymentRepository.sumAmountByBillId(bill.getId());
                if (otherPaid == null) otherPaid = BigDecimal.ZERO;
                otherPaid = otherPaid.subtract(pay.getAmount());
                BigDecimal maxAllowed = bill.getTotalAmount().subtract(otherPaid);
                if (req.getAmount().compareTo(maxAllowed) > 0)
                    throw new IllegalArgumentException(
                        String.format("Updated amount ₹%.2f exceeds bill balance ₹%.2f", req.getAmount(), maxAllowed));
            }
            pay.setAmount(req.getAmount());
        }

        InvPaymentEntity saved = paymentRepository.save(pay);

        // Recalculate linked bill status
        if (saved.getBillId() != null) {
            billRepository.findById(saved.getBillId()).ifPresent(bill -> {
                BigDecimal totalPaid = paymentRepository.sumAmountByBillId(bill.getId());
                bill.setPaidAmount(totalPaid != null ? totalPaid : BigDecimal.ZERO);
                bill.recalculateStatus();
                billRepository.save(bill);
            });
        }

        log.info("Updated payment {} by user {}", saved.getPaymentNo(), updatedBy);
        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        String billNo = saved.getBillId() != null
            ? billRepository.findById(saved.getBillId()).map(InvBillEntity::getBillNo).orElse(null)
            : null;
        return InvPaymentWrapper.from(saved, billNo, whName);
    }

    // ── Allocate advance to bills ─────────────────────────────────────────────

    /**
     * Allocates an advance payment (billId == null) to one or more unpaid bills.
     * Creates new InvPaymentEntity records linked to each bill,
     * sets advanceId on them, and increments appliedAmount on the advance.
     *
     * body: { "allocations": [ { "billId": 1, "amount": 5000.00 }, ... ] }
     */
    @Transactional
    public Map<String, Object> allocate(Long advanceId, Map<String, Object> body, Long userId) {
        InvPaymentEntity advance = paymentRepository.findById(advanceId)
            .orElseThrow(() -> new IllegalArgumentException("Advance payment not found: " + advanceId));
        if (advance.getBillId() != null)
            throw new IllegalArgumentException("Only advance payments (no bill link) can be allocated");

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> allocations = (java.util.List<Map<String, Object>>) body.get("allocations");
        if (allocations == null || allocations.isEmpty())
            throw new IllegalArgumentException("At least one allocation is required");

        BigDecimal available = advance.getAmount().subtract(
            advance.getAppliedAmount() != null ? advance.getAppliedAmount() : BigDecimal.ZERO);

        BigDecimal totalAllocating = allocations.stream()
            .map(a -> new BigDecimal(a.get("amount").toString()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAllocating.compareTo(available) > 0)
            throw new IllegalArgumentException(
                String.format("Total allocation ₹%.2f exceeds available advance balance ₹%.2f", totalAllocating, available));

        java.util.List<InvPaymentWrapper> created = new java.util.ArrayList<>();

        for (Map<String, Object> alloc : allocations) {
            Long billId = Long.parseLong(alloc.get("billId").toString());
            BigDecimal allocAmt = new BigDecimal(alloc.get("amount").toString());
            if (allocAmt.compareTo(BigDecimal.ZERO) <= 0) continue;

            InvBillEntity bill = billRepository.findById(billId)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + billId));

            BigDecimal balance = bill.getBalanceAmount();
            if (allocAmt.compareTo(balance) > 0)
                throw new IllegalArgumentException(
                    String.format("Allocation ₹%.2f exceeds bill %s balance ₹%.2f", allocAmt, bill.getBillNo(), balance));

            // Create a new BILL_PAYMENT linked to the bill, sourced from this advance
            InvPaymentEntity payment = InvPaymentEntity.builder()
                .paymentNo("PENDING")
                .billId(billId)
                .advanceId(advanceId)
                .vendorId(advance.getVendorId())
                .vendorName(advance.getVendorName())
                .warehouseId(advance.getWarehouseId())
                .groupName(advance.getGroupName())
                .subGroupName(advance.getSubGroupName())
                .projectId(advance.getProjectId())
                .paymentDate(advance.getPaymentDate())
                .amount(allocAmt)
                .paymentMode(advance.getPaymentMode())
                .referenceNumber("Alloc from " + advance.getPaymentNo())
                .notes("Allocated from advance " + advance.getPaymentNo())
                .createdBy(userId)
                .build();

            InvPaymentEntity saved = paymentRepository.saveAndFlush(payment);
            String payNo = String.format("INV-PAY-%d-%05d", java.time.LocalDate.now().getYear(), saved.getId());
            saved.setPaymentNo(payNo);
            saved = paymentRepository.saveAndFlush(saved);

            // Update bill paidAmount
            BigDecimal totalPaid = paymentRepository.sumAmountByBillId(billId);
            bill.setPaidAmount(totalPaid);
            bill.recalculateStatus();
            billRepository.save(bill);

            String whName = saved.getWarehouseId() != null
                ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null) : null;
            created.add(InvPaymentWrapper.from(saved, bill.getBillNo(), whName));

            log.info("Allocated ₹{} from advance {} to bill {}", allocAmt, advance.getPaymentNo(), bill.getBillNo());
        }

        // Update advance appliedAmount
        BigDecimal newApplied = (advance.getAppliedAmount() != null ? advance.getAppliedAmount() : BigDecimal.ZERO)
            .add(totalAllocating);
        advance.setAppliedAmount(newApplied);
        InvPaymentEntity savedAdvance = paymentRepository.save(advance);

        String advWhName = savedAdvance.getWarehouseId() != null
            ? warehouseRepository.findById(savedAdvance.getWarehouseId()).map(WarehouseEntity::getName).orElse(null) : null;

        return java.util.Map.of(
            "advance", InvPaymentWrapper.from(savedAdvance, null, advWhName),
            "allocations", created,
            "message", "Advance allocated successfully"
        );
    }

    // ── Delete (reverses bill paidAmount) ─────────────────────────────────────

    // Allocations for an advance
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllocations(Long advanceId) {
        InvPaymentEntity advance = paymentRepository.findById(advanceId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + advanceId));
        if (advance.getBillId() != null)
            throw new IllegalArgumentException("Only advance payments have allocations");

        List<Map<String, Object>> result = new ArrayList<>();
        for (InvPaymentEntity row : paymentRepository.findByAdvanceId(advanceId)) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("allocationId",   row.getId());
            item.put("paymentNo",      row.getPaymentNo());
            item.put("billId",         row.getBillId());
            item.put("allocatedAmount",row.getAmount());
            item.put("allocationDate", row.getPaymentDate());
            if (row.getBillId() != null) {
                billRepository.findById(row.getBillId()).ifPresent(bill -> {
                    item.put("billNo",          bill.getBillNo());
                    item.put("billTotalAmount", bill.getTotalAmount());
                    item.put("billPaidAmount",  bill.getPaidAmount());
                    item.put("billStatus",      bill.getStatus());
                    item.put("billBalance",     bill.getBalanceAmount());
                });
            }
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        InvPaymentEntity pay = paymentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + id));

        // If this payment was allocated from an advance, reverse the advance's appliedAmount
        if (pay.getAdvanceId() != null) {
            paymentRepository.findById(pay.getAdvanceId()).ifPresent(advance -> {
                BigDecimal current = advance.getAppliedAmount() != null ? advance.getAppliedAmount() : BigDecimal.ZERO;
                BigDecimal reversed = current.subtract(pay.getAmount());
                if (reversed.compareTo(BigDecimal.ZERO) < 0) reversed = BigDecimal.ZERO;
                advance.setAppliedAmount(reversed);
                paymentRepository.save(advance);
            });
        }

        // Reverse bill's paidAmount before deleting
        if (pay.getBillId() != null) {
            billRepository.findById(pay.getBillId()).ifPresent(bill -> {
                paymentRepository.delete(pay);
                BigDecimal totalPaid = paymentRepository.sumAmountByBillId(bill.getId());
                bill.setPaidAmount(totalPaid != null ? totalPaid : BigDecimal.ZERO);
                bill.recalculateStatus();
                billRepository.save(bill);
            });
        } else {
            paymentRepository.delete(pay);
        }

        return Map.of("success", true, "message", "Payment deleted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<Long, String> buildBillNoMap(List<InvPaymentEntity> payments) {
        Map<Long, String> m = new HashMap<>();
        payments.stream().map(InvPaymentEntity::getBillId).filter(Objects::nonNull)
                .collect(Collectors.toSet())
                .forEach(bid -> billRepository.findById(bid)
                    .ifPresent(b -> m.put(bid, b.getBillNo())));
        return m;
    }

    private Map<String, String> buildWarehouseNameMap(Set<Long> ids) {
        Map<String, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        warehouseRepository.findAllById(ids).forEach(w -> m.put(String.valueOf(w.getId()), w.getName()));
        return m;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}