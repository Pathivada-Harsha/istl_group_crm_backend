package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.service.OrderBookService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalItemWrapper;

@RestController
@RequestMapping("/order-book")
public class OrderBookController {

    @Autowired
    private OrderBookService orderBookService;

    // ─────────────────────────────────────────────────────────────────────────
    // GET ALL  —  GET /order-book/getAll
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllOrderBooks(
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "10")   int    size,
            @RequestParam(required = false)      String groupName,
            @RequestParam(required = false)      String subGroupName,
            @RequestParam(required = false)      String projectId,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            Page<OrderBookWrapper> orderBooks =
                orderBookService.getAllOrderBooks(page, size, groupName, subGroupName, projectId, userId, userRole);

            Map<String, Object> response = new HashMap<>();
            response.put("success",     true);
            response.put("data",        orderBooks.getContent());
            response.put("currentPage", orderBooks.getNumber());
            response.put("totalItems",  orderBooks.getTotalElements());
            response.put("totalPages",  orderBooks.getTotalPages());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEARCH  —  POST /order-book/search
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchOrderBooks(
            @RequestParam(required = false)     String searchTerm,
            @RequestParam(required = false)     String status,
            @RequestParam(required = false)     String groupName,
            @RequestParam(required = false)     String subGroupName,
            @RequestParam(required = false)     String fromDate,
            @RequestParam(required = false)     String toDate,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir,
            @RequestParam(defaultValue = "0")   int    page,
            @RequestParam(defaultValue = "10")  int    size,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            Page<OrderBookWrapper> results = orderBookService.searchOrderBooks(
                searchTerm, status, groupName, subGroupName, fromDate, toDate, sortBy, sortDir, page, size, userId, userRole);

            Map<String, Object> response = new HashMap<>();
            response.put("success",     true);
            response.put("data",        results.getContent());
            response.put("currentPage", results.getNumber());
            response.put("totalItems",  results.getTotalElements());
            response.put("totalPages",  results.getTotalPages());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET BY ID  —  GET /order-book/{id}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOrderBookById(
            @PathVariable Long id,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            OrderBookWrapper orderBook = orderBookService.getOrderBookById(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    orderBook);
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            return errorResponse(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return errorResponse("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET ITEMS  —  GET /order-book/{id}/items
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/items")
    public ResponseEntity<Map<String, Object>> getOrderBookItems(
            @PathVariable Long id,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            List<OrderBookItemWrapper> items = orderBookService.getOrderBookItems(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    items);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET ITEMS WITH TRACKING  —  GET /order-book/{id}/items-with-tracking
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/items-with-tracking")
    public ResponseEntity<Map<String, Object>> getOrderBookItemsWithTracking(
            @PathVariable Long id,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            List<OrderBookItemWrapper> items =
                orderBookService.getOrderBookItemsWithTracking(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    items);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return errorResponse(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET PROPOSAL BOM ITEMS  —  GET /order-book/proposal-items/{proposalId}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/proposal-items/{proposalId}")
    public ResponseEntity<Map<String, Object>> getProposalItems(
            @PathVariable Long proposalId,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            List<Map<String, Object>> items = orderBookService.getProposalBomItems(proposalId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    items);
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            return errorResponse(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return errorResponse(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE  —  POST /order-book/create
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createOrderBook(
            @RequestBody OrderBookRequestWrapper request,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            OrderBookWrapper created = orderBookService.createOrderBook(request, userId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Order book created successfully");
            response.put("data",    created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (CustomException e) {
            return errorResponse(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return errorResponse("Failed to create order book: " + e.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE  —  PUT /order-book/update/{id}
    // ─────────────────────────────────────────────────────────────────────────
    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> updateOrderBook(
            @PathVariable Long id,
            @RequestBody OrderBookRequestWrapper request,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            OrderBookWrapper updated = orderBookService.updateOrderBook(id, request, userId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Order book updated successfully");
            response.put("data",    updated);
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            return errorResponse(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return errorResponse("Failed to update order book",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPLOAD PO FILE  —  POST /order-book/{id}/upload-po
    // Stores the file as LONGBLOB in the database (no disk writes).
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{id}/upload-po")
    public ResponseEntity<Map<String, Object>> uploadPOFile(
            @PathVariable Long id,
            @RequestParam("file")                          MultipartFile file,
            @RequestParam("poNumber")                      String        poNumber,
            @RequestParam(value = "poDate", required = false) String     poDate,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            OrderBookWrapper updated =
                orderBookService.uploadPOFile(id, file, poNumber, poDate);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "PO file uploaded successfully");
            response.put("data",    updated);
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            return errorResponse(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return errorResponse("Failed to upload PO file",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD PO FILE  —  GET /order-book/{id}/download-po
    //
    // Streams the LONGBLOB back to the browser.
    //   • PDFs and images  →  Content-Disposition: inline   (renders in browser)
    //   • Other types      →  Content-Disposition: attachment (forces download)
    //   • ?forceDownload=true always forces a browser download
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/download-po")
    public ResponseEntity<byte[]> downloadPoFile(
            @PathVariable Long id,
            @RequestParam(value = "forceDownload", defaultValue = "false") boolean forceDownload,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            OrderBookEntity entity =
                orderBookService.getOrderBookEntityForDownload(id);

            String mimeType  = entity.getPoFileMimeType() != null
                ? entity.getPoFileMimeType() : "application/octet-stream";
            String fileName  = entity.getPoFileName() != null
                ? entity.getPoFileName() : "attachment";

            boolean inline   = !forceDownload && isInlineMimeType(mimeType);
            String  disposition = (inline ? "inline" : "attachment")
                + "; filename=\"" + fileName + "\"";

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(mimeType))
                .contentLength(entity.getPoFileData().length)
                .body(entity.getPoFileData());

        } catch (CustomException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE  —  DELETE /order-book/delete/{id}
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> deleteOrderBook(
            @PathVariable Long id,
            @ActingUserId   Long   userId,
            @ActingUserRole String userRole) {
        try {
            orderBookService.deleteOrderBook(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Order book deleted successfully");
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            return errorResponse(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return errorResponse("Failed to delete order book",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** PDFs and common image types should render inline in the browser */
    private boolean isInlineMimeType(String mimeType) {
        return mimeType.equals("application/pdf")
            || mimeType.startsWith("image/");
    }

    /** Convenience: build a standard error response body */
    private ResponseEntity<Map<String, Object>> errorResponse(String message, HttpStatus status) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}