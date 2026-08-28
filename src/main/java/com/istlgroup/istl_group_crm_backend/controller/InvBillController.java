package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.service.InvBillService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvBillWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/inventory/bills")
@RequiredArgsConstructor
@Slf4j
public class InvBillController {

    private final InvBillService billService;

    /** GET /inventory/bills?warehouseId=&groupName=&status=&search=&page=0&size=20 */
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
                billService.list(warehouseId, groupName, subGroupName, projectId, status, vendorId, search, page, size));
        } catch (Exception e) {
            log.error("Failed to list inventory bills", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load bills"));
        }
    }

    /** GET /inventory/bills/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(billService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to get bill {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load bill"));
        }
    }

    /**
     * GET /inventory/bills/{id}/payments
     * Returns all payment records linked to this bill, newest first.
     * Includes advance-allocation rows, with source advance payment_no enriched.
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<?> getPayments(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(billService.getPayments(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to get payments for bill {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load bill payments"));
        }
    }

    /** POST /inventory/bills */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody InvBillWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(billService.create(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create inventory bill", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to create bill"));
        }
    }

    /** PUT /inventory/bills/{id} */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody InvBillWrapper body) {
        try {
            return ResponseEntity.ok(billService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update bill {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to update bill"));
        }
    }

    /** DELETE /inventory/bills/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(billService.delete(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete bill {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to delete bill"));
        }
    }
}