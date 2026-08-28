package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.service.InvPurchaseOrderService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvPurchaseOrderWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/inventory/purchase-orders")
@RequiredArgsConstructor
@Slf4j
public class InvPurchaseOrderController {

    private final InvPurchaseOrderService poService;

    /** GET /inventory/purchase-orders?warehouseId=&groupName=&subGroupName=&projectId=&status=&search=&page=0&size=20 */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) Long   warehouseId,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long   vendorId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(
                poService.list(warehouseId, groupName, subGroupName, projectId, status, vendorId, search, page, size));
        } catch (Exception e) {
            log.error("Failed to list inventory POs", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load purchase orders"));
        }
    }

    /** GET /inventory/purchase-orders/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(poService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to get PO {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load purchase order"));
        }
    }

    /** POST /inventory/purchase-orders */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody InvPurchaseOrderWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(poService.create(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create inventory PO", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to create purchase order"));
        }
    }

    /** PATCH /inventory/purchase-orders/{id}/status */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @ActingUserId Long userId) {
        try {
            String newStatus = body.get("status");
            if (newStatus == null || newStatus.isBlank())
                return ResponseEntity.badRequest().body(Map.of("message", "status is required"));
            return ResponseEntity.ok(poService.updateStatus(id, newStatus, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update PO {} status", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to update status"));
        }
    }

    /** PUT /inventory/purchase-orders/{id} — update full PO (header + line items) */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody InvPurchaseOrderWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(poService.update(id, body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update PO {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to update purchase order"));
        }
    }

    /**
     * POST /inventory/purchase-orders/{id}/receive
     * body: { "lines": { "42": 5.0, "43": 10.0 } }  — poItemId → receivedQty
     */
    @PostMapping("/{id}/receive")
    public ResponseEntity<?> receiveGoods(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @ActingUserId Long userId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Number> rawLines = (Map<String, Number>) body.get("lines");
            if (rawLines == null || rawLines.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("message", "lines map is required"));

            Map<Long, BigDecimal> lines = new java.util.HashMap<>();
            rawLines.forEach((k, v) -> lines.put(Long.parseLong(k), new BigDecimal(v.toString())));

            return ResponseEntity.ok(poService.receiveGoods(id, lines, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to receive goods for PO {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to record goods receipt"));
        }
    }

    /** GET /inventory/purchase-orders/{id}/items-for-bill
     * Returns PO items enriched with orderedQty, receivedQty, pendingQty —
     * used by the Create Bill modal to show what still needs to be invoiced.
     */
    @GetMapping("/{id}/items-for-bill")
    public ResponseEntity<?> itemsForBill(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(poService.getItemsForBill(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to get PO items for bill {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load items"));
        }
    }

    /** GET /inventory/purchase-orders/modal/vendors
     * Returns vendors that have inv_purchase_orders matching the given scope.
     * Scoped to groupName / subGroupName / projectId — same pattern as BillsManagementPage.
     */
    @GetMapping("/modal/vendors")
    public ResponseEntity<?> modalVendors(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId) {
        try {
            return ResponseEntity.ok(poService.getVendorsForScope(groupName, subGroupName, projectId));
        } catch (Exception e) {
            log.error("Failed to get modal vendors", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load vendors"));
        }
    }

    /** DELETE /inventory/purchase-orders/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(poService.delete(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete PO {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to delete purchase order"));
        }
    }

    /**
     * GET /inventory/purchase-orders/project/{projectId}/all-items
     *
     * Returns ALL PO line items (across every non-cancelled PO) for a project,
     * enriched with poNo, orderedQty, receivedQty, pendingQty, unitPrice.
     * Used by the INWARD transaction modal to show a flat item picker.
     */
    @GetMapping("/project/{projectId}/all-items")
    public ResponseEntity<?> allItemsByProject(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(poService.getAllItemsByProject(projectId));
        } catch (Exception e) {
            log.error("Failed to get PO items for project {}", projectId, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load project PO items"));
        }
    }
}