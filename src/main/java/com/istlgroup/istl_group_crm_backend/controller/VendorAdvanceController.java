package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.VendorAdvanceEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import com.istlgroup.istl_group_crm_backend.service.VendorAdvanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/vendor-advances")
@RequiredArgsConstructor
@Slf4j
public class VendorAdvanceController {

    private final VendorAdvanceService advanceService;
    private final VendorRepository vendorRepository;
    private final ProjectRepository projectRepository;

    // ─── LIST ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAdvances(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String paymentType,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String paymentDateFrom,
            @RequestParam(required = false) String paymentDateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "advanceDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        try {
            Page<VendorAdvanceEntity> advances = advanceService.getAdvances(
                    groupId, subGroupId, projectId, paymentType, vendorId,
                    searchTerm, paymentDateFrom, paymentDateTo, page, size, sortBy, sortDirection);

            List<Map<String, Object>> enriched = advances.getContent().stream().map(a -> {
                Map<String, Object> m = entityToMap(a);
                vendorRepository.findById(a.getVendorId()).ifPresent(v -> m.put("vendorName", v.getName()));
                return m;
            }).toList();

            List<String> projectIds = advances.getContent().stream()
                .map(VendorAdvanceEntity::getProjectId)
                .filter(pid -> pid != null && !pid.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
            Map<String, String> projectNameMap = new HashMap<>();
            if (!projectIds.isEmpty()) {
                projectRepository.findByProjectUniqueIdIn(projectIds)
                    .forEach(p -> projectNameMap.put(p.getProjectUniqueId(), p.getProjectName()));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("advances",     enriched);
            response.put("projectNames", projectNameMap);
            response.put("currentPage",  advances.getNumber());
            response.put("totalPages",   advances.getTotalPages());
            response.put("totalElements",advances.getTotalElements());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching vendor advances", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─── GET BY ID ───────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            VendorAdvanceEntity a = advanceService.getById(id);
            Map<String, Object> m = entityToMap(a);
            vendorRepository.findById(a.getVendorId()).ifPresent(v -> m.put("vendorName", v.getName()));
            return ResponseEntity.ok(m);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── CREATE ───────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> createAdvance(
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Id") Long userId) {
        try {
            VendorAdvanceEntity advance = mapToEntity(body);
            VendorAdvanceEntity saved = advanceService.createAdvance(advance, userId);
            Map<String, Object> m = entityToMap(saved);
            vendorRepository.findById(saved.getVendorId()).ifPresent(v -> m.put("vendorName", v.getName()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Payment recorded successfully", "data", m));
        } catch (Exception e) {
            log.error("Error creating vendor advance", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> updateAdvance(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Id") Long userId) {
        try {
            VendorAdvanceEntity updated = mapToEntity(body);
            VendorAdvanceEntity saved = advanceService.updateAdvance(id, updated, userId);
            return ResponseEntity.ok(Map.of("message", "Updated successfully", "data", entityToMap(saved)));
        } catch (Exception e) {
            log.error("Error updating vendor advance {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAdvance(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) {
        try {
            advanceService.deleteAdvance(id, userId);
            return ResponseEntity.ok(Map.of("message", "Deleted successfully"));
        } catch (Exception e) {
            log.error("Error deleting vendor advance {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── RESTORE ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restoreAdvance(@PathVariable Long id) {
        try {
            VendorAdvanceEntity restored = advanceService.restoreAdvance(id);
            return ResponseEntity.ok(Map.of("message", "Restored successfully", "data", entityToMap(restored)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── ALLOCATE ADVANCE → BILLS ─────────────────────────────────────────────

    @PostMapping("/{id}/allocate")
    public ResponseEntity<?> allocate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader("X-User-Id") Long userId) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allocations = (List<Map<String, Object>>) payload.get("allocations");
            if (allocations == null || allocations.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("message", "No allocations provided"));

            VendorAdvanceEntity updated = advanceService.allocateAdvanceToBills(id, allocations, userId);
            return ResponseEntity.ok(Map.of("message", "Advance allocated successfully", "data", entityToMap(updated)));
        } catch (Exception e) {
            log.error("Error allocating advance {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── GET ALLOCATION DETAILS ───────────────────────────────────────────────

    @GetMapping("/{id}/allocations")
    public ResponseEntity<?> getAllocationDetails(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(advanceService.getAllocationDetails(id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── REMOVE ONE ALLOCATION ────────────────────────────────────────────────

    @DeleteMapping("/{advanceId}/allocations/{billId}")
    public ResponseEntity<?> removeAllocation(
            @PathVariable Long advanceId,
            @PathVariable Long billId,
            @RequestHeader("X-User-Id") Long userId) {
        try {
            VendorAdvanceEntity updated = advanceService.removeAllocation(advanceId, billId, userId);
            return ResponseEntity.ok(Map.of("message", "Allocation removed", "data", entityToMap(updated)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── SUMMARY ─────────────────────────────────────────────────────────────

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String paymentType,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String paymentDateFrom,
            @RequestParam(required = false) String paymentDateTo) {
        try {
            return ResponseEntity.ok(advanceService.getSummary(groupId, subGroupId, projectId, paymentType, searchTerm, paymentDateFrom, paymentDateTo));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── UNAPPLIED ADVANCES FOR A VENDOR ─────────────────────────────────────

    @GetMapping("/vendor/{vendorId}/unapplied")
    public ResponseEntity<?> getUnappliedAdvances(@PathVariable Long vendorId) {
        try {
            return ResponseEntity.ok(advanceService.getUnappliedAdvancesForVendor(vendorId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── DELETED LIST ─────────────────────────────────────────────────────────

    @GetMapping("/deleted")
    public ResponseEntity<?> getDeleted() {
        try {
            return ResponseEntity.ok(advanceService.getDeletedAdvances());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── ALLOCATIONS LINKED TO A BILL ────────────────────────────────────────

    @GetMapping("/bill/{billId}/allocations")
    public ResponseEntity<?> getAllocationsForBill(@PathVariable Long billId) {
        try {
            return ResponseEntity.ok(advanceService.getAllocationsForBill(billId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── PAYABLE BILLS FOR VENDOR + PROJECT (DEDICATED ENDPOINT) ─────────────
    //
    // This endpoint is used ONLY by the Vendor Payments modal:
    //   1. "Payment Against Bill" — bill selector
    //   2. "Allocate Advance"    — bill list in allocation modal
    //
    // It is completely isolated from GET /bills used by Bills Received page.
    // Changes to /bills will NEVER affect this endpoint, and vice-versa.

    /**
     * GET /api/vendor-advances/payable-bills
     *
     * Returns Pending + Partially-Paid bills for a given vendor, optionally
     * scoped to a project.
     *
     * Query params:
     *   vendorId  (required) — numeric vendor ID
     *   projectId (optional) — project unique-ID string (e.g. PROJ-2026-0011)
     *             When omitted, returns all payable bills across all projects.
     */
    @GetMapping("/payable-bills")
    public ResponseEntity<?> getPayableBills(
            @RequestParam Long vendorId,
            @RequestParam(required = false) String projectId) {
        try {
            List<BillEntity> bills = advanceService.getPayableBillsForVendor(vendorId, projectId);
            List<Map<String, Object>> result = bills.stream().map(b -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id",            b.getId());
                m.put("billNo",        b.getBillNo());
                m.put("billRefId",     b.getBillRefId());
                m.put("billDate",      b.getBillDate());
                m.put("dueDate",       b.getDueDate());
                m.put("totalAmount",   b.getTotalAmount());
                m.put("paidAmount",    b.getPaidAmount());
                m.put("balanceAmount", b.getBalanceAmount());
                m.put("status",        b.getStatus());
                m.put("projectId",     b.getProjectId());
                m.put("vendorId",      b.getVendorId());
                return m;
            }).toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching payable bills for vendor {}", vendorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    // ─── PRIVATE HELPERS ─────────────────────────────────────────────────────

    private VendorAdvanceEntity mapToEntity(Map<String, Object> body) {
        VendorAdvanceEntity e = new VendorAdvanceEntity();
        if (body.get("vendorId") != null)
            e.setVendorId(Long.valueOf(body.get("vendorId").toString()));
        if (body.get("billId") != null)
            e.setBillId(Long.valueOf(body.get("billId").toString()));
        if (body.get("paymentType") != null)
            e.setPaymentType(body.get("paymentType").toString());
        if (body.get("advanceDate") != null)
            e.setAdvanceDate(LocalDate.parse(body.get("advanceDate").toString()));
        if (body.get("amount") != null)
            e.setAmount(new BigDecimal(body.get("amount").toString()));
        if (body.get("paymentMode") != null)
            e.setPaymentMode(body.get("paymentMode").toString());
        if (body.get("transactionReference") != null)
            e.setTransactionReference(body.get("transactionReference").toString());
        if (body.get("notes") != null)
            e.setNotes(body.get("notes").toString());
        if (body.get("projectId") != null)
            e.setProjectId(body.get("projectId").toString());
        if (body.get("groupId") != null)
            e.setGroupId(body.get("groupId").toString());
        if (body.get("subGroupId") != null)
            e.setSubGroupId(body.get("subGroupId").toString());
        if (body.get("company") != null)
            e.setCompany(body.get("company").toString());
        return e;
    }

    private Map<String, Object> entityToMap(VendorAdvanceEntity a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",                   a.getId());
        m.put("advanceNo",            a.getAdvanceNo());
        m.put("advanceDate",          a.getAdvanceDate());
        m.put("paymentType",          a.getPaymentType());
        m.put("vendorId",             a.getVendorId());
        m.put("billId",               a.getBillId());
        m.put("projectId",            a.getProjectId());
        m.put("groupId",              a.getGroupId());
        m.put("subGroupId",           a.getSubGroupId());
        m.put("company",              a.getCompany());
        m.put("amount",               a.getAmount());
        m.put("appliedAmount",        a.getAppliedAmount());
        m.put("unappliedAmount",      a.getUnappliedAmount());
        m.put("paymentMode",          a.getPaymentMode());
        m.put("transactionReference", a.getTransactionReference());
        m.put("notes",                a.getNotes());
        m.put("createdAt",            a.getCreatedAt());
        m.put("deletedAt",            a.getDeletedAt());
        return m;
    }
}