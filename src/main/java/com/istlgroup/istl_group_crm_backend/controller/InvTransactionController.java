package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.InvTransactionService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvTransactionWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/inventory/transactions")
@RequiredArgsConstructor
@Slf4j
public class InvTransactionController {

    private final InvTransactionService txnService;

    /** GET /inventory/transactions */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) Long   warehouseId,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long   inventoryItemId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            return ResponseEntity.ok(
                txnService.list(warehouseId, groupName, subGroupName,
                                projectId, type, inventoryItemId, search, page, size));
        } catch (Exception e) {
            log.error("Failed to list inventory transactions", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to load transactions"));
        }
    }

    /** POST /inventory/transactions — create single transaction */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody InvTransactionWrapper body,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            return ResponseEntity.ok(txnService.create(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create inventory transaction", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to record transaction"));
        }
    }

    /** GET /inventory/transactions/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(txnService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load transaction"));
        }
    }

    /** DELETE /inventory/transactions/{id} — hard delete, reverses stock */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            return ResponseEntity.ok(txnService.deleteTransaction(id, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete inventory transaction {}", id, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to delete transaction"));
        }
    }

    /** PATCH /inventory/transactions/{id} — update editable metadata only */
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody InvTransactionWrapper body) {
        try {
            return ResponseEntity.ok(txnService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update inventory transaction {}", id, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to update transaction"));
        }
    }

    /** POST /inventory/transactions/batch-inward — receive multiple items from site */
    @PostMapping("/batch-inward")
    public ResponseEntity<?> batchInward(
            @RequestBody InvTransactionWrapper body,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            body.setType("INWARD");
            return ResponseEntity.ok(txnService.createBatchInward(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process batch inward transaction", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to record batch inward: " + e.getMessage()));
        }
    }

    /** POST /inventory/transactions/batch-outward — issue items from warehouse, auto-creates bill */
    @PostMapping("/batch-outward")
    public ResponseEntity<?> batchOutward(
            @RequestBody InvTransactionWrapper body,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            body.setType("OUTWARD");
            return ResponseEntity.ok(txnService.createBatch(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process batch outward transaction", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to record batch transaction: " + e.getMessage()));
        }
    }
}