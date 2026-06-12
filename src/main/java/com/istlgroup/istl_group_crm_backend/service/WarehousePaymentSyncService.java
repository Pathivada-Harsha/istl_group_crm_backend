package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Records a vendor payment against the auto-generated warehouse bill
 * every time stock is issued (OUTWARD) from a warehouse to a site.
 *
 * Called from InvTransactionService.createBatch after createWarehouseBill
 * commits (REQUIRES_NEW) so the bill row is visible here.
 *
 * Runs in its own REQUIRES_NEW transaction.
 *
 * NOTE: projectId is intentionally passed as null to the VendorAdvanceEntity
 * to prevent VendorAdvanceService.createAdvance from calling
 * projectStatsService.updateProjectAfterBillPayment — which would throw
 * "Project not found" because inventory projectIds are not in the CRM
 * projects table. Bill-level project stats are already synced by
 * BillService.syncProjectBillStats called after createWarehouseBill.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WarehousePaymentSyncService {

    private final VendorRepository        vendorRepository;
    private final BillRepository          billRepository;
    private final WarehouseRepository     warehouseRepository;
    private final VendorAdvanceService    vendorAdvanceService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncPayments(
            Long        billId,
            String      billNo,
            BigDecimal  totalAmount,
            String      invTxnRef,
            String      projectId,
            String      groupId,
            String      subGroupId,
            Long        warehouseId,
            String      warehouseName,
            Long        userId) {

        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("WarehousePaymentSync: zero-value bill {}, skipping", billNo);
            return;
        }

        // ── 1. Find or create system vendor ──────────────────────────────────
        String warehouseCode = warehouseId != null
            ? warehouseRepository.findById(warehouseId)
                .map(WarehouseEntity::getCode)
                .orElse(warehouseName)
            : warehouseName;

        String systemVendorName = "[WH] " + warehouseCode;

        VendorEntity vendor = vendorRepository.findByName(systemVendorName)
            .orElseGet(() -> {
                VendorEntity v = new VendorEntity();
                v.setName(systemVendorName);
                v.setCategory("Warehouse");
                v.setVendorType("Warehouse");
                v.setStatus("Active");
                v.setVendorCode("WHVND-" + warehouseCode);
                v.setProjectId("WAREHOUSE"); // NOT NULL constraint requires a value
                log.info("WarehousePaymentSync: auto-creating vendor '{}'", systemVendorName);
                return vendorRepository.save(v);
            });

        // ── 2. Link vendorId to the bill row ─────────────────────────────────
        billRepository.findById(billId).ifPresent(bill -> {
            bill.setVendorId(vendor.getId());
            billRepository.save(bill);
        });

        // ── 3. Record payment via VendorAdvanceService.createAdvance ──────────
        // projectId is set to null intentionally — prevents createAdvance from
        // calling projectStatsService.updateProjectAfterBillPayment which would
        // throw "Project not found" for inventory-scoped project IDs.
        VendorAdvanceEntity advance = VendorAdvanceEntity.builder()
            .advanceDate(LocalDate.now())
            .paymentType(VendorAdvanceEntity.PaymentType.BILL_PAYMENT)
            .vendorId(vendor.getId())
            .billId(billId)
            .projectId(projectId)  // real projectId so sumAdvanceAmountByProjectId counts it
            .groupId(groupId)
            .subGroupId(subGroupId)
            .amount(totalAmount)
            .appliedAmount(BigDecimal.ZERO)
            .paymentMode("Warehouse Stock")
            .transactionReference(invTxnRef)
            .notes("Warehouse stock issued to site. Bill: " + billNo + ". Ref: " + invTxnRef)
            .createdBy(userId)
            .build();

        VendorAdvanceEntity saved = vendorAdvanceService.createAdvance(advance, userId);
        log.info("WarehousePaymentSync: created {} for bill {} (vendor={}, amount={})",
                 saved.getAdvanceNo(), billNo, systemVendorName, totalAmount);
    }
}