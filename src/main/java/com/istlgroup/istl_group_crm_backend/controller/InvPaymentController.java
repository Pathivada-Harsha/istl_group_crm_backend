package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.service.InvPaymentService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvPaymentWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/inventory/payments")
@RequiredArgsConstructor
@Slf4j
public class InvPaymentController {

    private final InvPaymentService paymentService;

    /**
     * GET /inventory/payments
     *   ?groupName=&subGroupName=&projectId=&vendorId=
     *   &paymentType=ADVANCE|BILL_PAYMENT  (optional, default: all)
     *   &paymentMode=Bank+Transfer|UPI|... (optional, default: all)
     *   &search=&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) Long   vendorId,
            @RequestParam(required = false) String paymentType,
            @RequestParam(required = false) String paymentMode,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(
                paymentService.list(groupName, subGroupName, projectId, vendorId,
                                    paymentType, paymentMode, search, page, size));
        } catch (Exception e) {
            log.error("Failed to list inventory payments", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load payments"));
        }
    }

    /** POST /inventory/payments */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody InvPaymentWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(paymentService.create(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create inventory payment", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to record payment"));
        }
    }

    /** PUT /inventory/payments/{id} — update non-structural fields (date, amount, mode, reference, notes) */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody InvPaymentWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(paymentService.update(id, body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update payment {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to update payment"));
        }
    }

    /**
     * GET /inventory/payments/{id}/allocations
     * Returns all BILL_PAYMENT rows created from this advance (advance_id = {id}),
     * enriched with bill details (billNo, totalAmount, paidAmount, status, balance).
     */
    @GetMapping("/{id}/allocations")
    public ResponseEntity<?> getAllocations(
            @PathVariable Long id) {
        try {
            return ResponseEntity.ok(paymentService.getAllocations(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to get allocations for payment {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to load allocations"));
        }
    }

    /**
     * POST /inventory/payments/{id}/allocate
     * Allocates an advance payment to one or more bills.
     * body: { "allocations": [ { "billId": 1, "amount": 5000.00 }, ... ] }
     */
    @PostMapping("/{id}/allocate")
    public ResponseEntity<?> allocate(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(paymentService.allocate(id, body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to allocate advance payment {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to allocate advance"));
        }
    }

    /**
     * DELETE /inventory/payments/{id}
     * Deletes a payment and reverses the bill's paidAmount/status.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(paymentService.delete(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete payment {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to delete payment"));
        }
    }
}