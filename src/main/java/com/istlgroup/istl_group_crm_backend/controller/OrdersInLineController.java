// ─────────────────────────────────────────────────────────────────────────────
// PROVISIONAL FEATURE — "Orders in Line"
// Temporary stopgap register, scheduled for replacement by a permanent pipeline
// module. Data here migrates into the leads table at that point.
// Removal: drop table `orders_in_line`, delete the OrdersInLine* files, revert the
// two lines in Dashboard.js, the sidebar entry, and the App.js import + route.
// ─────────────────────────────────────────────────────────────────────────────
package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.OrdersInLineService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrdersInLineWrapper;

/**
 * Orders-in-Line REST API. Mirrors the Tender controller conventions: a
 * {@code {success, message, data}} envelope and the {@code User-Id} request
 * header for the current user. Protected by SessionFilter like every non-login
 * endpoint, so no security config change is needed.
 *
 * <p>No new permission code: the page reuses the existing Leads permission on
 * the frontend, which is where page permissions are enforced in this codebase.
 */
@RestController
@RequestMapping("/orders-in-line")
public class OrdersInLineController {

    @Autowired
    private OrdersInLineService ordersInLineService;

    /** All non-deleted records. Every filter is optional and combines with AND. */
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        try {
            List<OrdersInLineWrapper> data =
                    ordersInLineService.list(search, status, category, fromDate, toDate);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", data);
            res.put("count", data.size());
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to load orders in line: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        try {
            OrdersInLineWrapper data = ordersInLineService.getById(id);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", data);
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return error("Failed to load order in line: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody OrdersInLineWrapper request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            OrdersInLineWrapper created = ordersInLineService.create(request, userId);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Order in line created successfully");
            res.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to create order in line: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody OrdersInLineWrapper request) {
        try {
            OrdersInLineWrapper updated = ordersInLineService.update(id, request);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Order in line updated successfully");
            res.put("data", updated);
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update order in line: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Soft delete — the row stays in the database with a deleted_at timestamp. */
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            ordersInLineService.delete(id);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Order in line deleted successfully");
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return error("Failed to delete order in line: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Aggregate for the dashboard block only. Its own endpoint by design — the
     * existing admin dashboard payload is never extended, so removing this
     * feature never means editing the dashboard service or DTO.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        try {
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", ordersInLineService.summary());
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return error("Failed to load summary: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<Map<String, Object>> error(String message, HttpStatus status) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", false);
        res.put("message", message);
        return ResponseEntity.status(status).body(res);
    }
}
