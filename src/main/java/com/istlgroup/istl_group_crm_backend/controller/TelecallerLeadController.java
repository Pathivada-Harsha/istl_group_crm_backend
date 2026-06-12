package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.TelecallerLeadService;
import com.istlgroup.istl_group_crm_backend.service.FollowupsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TelecallerStatusUpdateRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Restricted controller for Telecaller users.
 */
@RestController
@RequestMapping("/telecaller")
public class TelecallerLeadController {

    @Autowired private TelecallerLeadService telecallerLeadService;
    @Autowired private FollowupsService followupsService;

    // ── GET /telecaller/my-leads ──────────────────────────────────────────────
    @GetMapping("/my-leads")
    public ResponseEntity<Map<String, Object>> getMyLeads(
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "20")  int size,
            @RequestParam(required = false)    String telecallerStatus,
            @RequestParam(required = false)    String assignedFrom,
            @RequestParam(required = false)    String assignedTo,
            @RequestParam(required = false)    String searchTerm,
            @RequestParam(required = false)    String groupName,
            @RequestParam(required = false)    String subGroupName,
            @RequestParam(required = false)    String priority,
            @RequestParam(required = false)    String source) {
        try {
            ensureTelecaller(userRole);
            int safeSize = (size > 0 && size <= 200) ? size : 20;

            LocalDate fromDate = null, toDate = null;
            try {
                if (assignedFrom != null && !assignedFrom.isBlank())
                    fromDate = LocalDate.parse(assignedFrom);
                if (assignedTo != null && !assignedTo.isBlank())
                    toDate = LocalDate.parse(assignedTo);
            } catch (DateTimeParseException ignored) {}

            var result = telecallerLeadService.getLeadsForTelecaller(
                    userId, telecallerStatus, PageRequest.of(page, safeSize),
                    fromDate, toDate,
                    (searchTerm != null && !searchTerm.isBlank()) ? searchTerm.trim() : null,
                    (groupName   != null && !groupName.isBlank())  ? groupName.trim()   : null,
                    (subGroupName != null && !subGroupName.isBlank()) ? subGroupName.trim() : null,
                    (priority    != null && !priority.isBlank())   ? priority.trim()    : null,
                    (source      != null && !source.isBlank())     ? source.trim()      : null);

            Map<String, Object> response = new HashMap<>();
            response.put("success",     true);
            response.put("data",        result.getContent());
            response.put("count",       result.getTotalElements());
            response.put("totalPages",  result.getTotalPages());
            response.put("currentPage", result.getNumber());
            response.put("pageSize",    result.getSize());
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── GET /telecaller/lead/{leadId} ─────────────────────────────────────────
    @GetMapping("/lead/{leadId}")
    public ResponseEntity<Map<String, Object>> getLeadDetail(
            @PathVariable               Long leadId,
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            ensureTelecaller(userRole);
            var lead = telecallerLeadService.getLeadDetailForTelecaller(leadId, userId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    lead);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── PUT /telecaller/lead/{leadId}/status ──────────────────────────────────
    @PutMapping("/lead/{leadId}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable               Long leadId,
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody TelecallerStatusUpdateRequest req) {
        try {
            ensureTelecaller(userRole);
            telecallerLeadService.updateTelecallerStatus(leadId, userId, req);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Status updated successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── GET /telecaller/dashboard-stats ──────────────────────────────────────
    // Now accepts the same filter params as my-leads so stats match the view
    @GetMapping("/dashboard-stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats(
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(required = false) String assignedFrom,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String source) {
        try {
            ensureTelecaller(userRole);

            LocalDate fromDate = null, toDate = null;
            try {
                if (assignedFrom != null && !assignedFrom.isBlank())
                    fromDate = LocalDate.parse(assignedFrom);
                if (assignedTo != null && !assignedTo.isBlank())
                    toDate = LocalDate.parse(assignedTo);
            } catch (DateTimeParseException ignored) {}

            var stats = telecallerLeadService.getDashboardStats(
                    userId, fromDate, toDate,
                    (groupName    != null && !groupName.isBlank())    ? groupName.trim()    : null,
                    (subGroupName != null && !subGroupName.isBlank()) ? subGroupName.trim() : null,
                    (priority     != null && !priority.isBlank())     ? priority.trim()     : null,
                    (source       != null && !source.isBlank())       ? source.trim()       : null);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    stats);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── PUT /telecaller/lead/{leadId}/details ─────────────────────────────────
    @PutMapping("/lead/{leadId}/details")
    public ResponseEntity<Map<String, Object>> updateLeadDetails(
            @PathVariable               Long leadId,
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody                Map<String, String> fields) {
        try {
            ensureTelecaller(userRole);
            var updated = telecallerLeadService.updateLeadDetails(leadId, userId, fields);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead updated successfully");
            response.put("data",    updated);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── POST /telecaller/lead/{leadId}/followup ───────────────────────────────
    @PostMapping("/lead/{leadId}/followup")
    public ResponseEntity<Map<String, Object>> createFollowup(
            @PathVariable               Long leadId,
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody                FollowupRequestWrapper req) {
        try {
            ensureTelecaller(userRole);
            req.setRelatedType("LEAD");
            req.setRelatedId(leadId);
            req.setLeadId(leadId);
            req.setAssignedTo(userId);
            if (req.getStatus() == null) req.setStatus("Pending");
            if (req.getFollowupType() == null) req.setFollowupType("Call");
            var result = followupsService.createFollowup(req, userId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Follow-up scheduled successfully");
            response.put("data",    result);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── POST /telecaller/lead/{leadId}/upload-bill ────────────────────────────
    @PostMapping(value = "/lead/{leadId}/upload-bill", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBillFile(
            @PathVariable               Long leadId,
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam("file")       org.springframework.web.multipart.MultipartFile file) {
        try {
            ensureTelecaller(userRole);
            if (file.isEmpty()) throw new CustomException("No file provided");
            long maxSize = 10L * 1024 * 1024;
            if (file.getSize() > maxSize) throw new CustomException("File size exceeds 10 MB limit");
            telecallerLeadService.saveBillFileToDb(leadId, userId, file);
            Map<String, Object> response = new HashMap<>();
            response.put("success",  true);
            response.put("message",  "Bill uploaded successfully");
            response.put("fileName", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── GET /telecaller/lead/{leadId}/bill ────────────────────────────────────
    @GetMapping("/lead/{leadId}/bill")
    public ResponseEntity<?> downloadBillFile(
            @PathVariable               Long leadId,
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            var lead = telecallerLeadService.getLeadEntityForBill(leadId, userId, userRole);
            if (lead.getTcBillFileData() == null || lead.getTcBillFileData().length == 0) {
                return error(HttpStatus.NOT_FOUND, "No bill file found for this lead");
            }
            String mime     = lead.getTcBillFileType() != null ? lead.getTcBillFileType() : "application/octet-stream";
            String fileName = lead.getTcBillFileName() != null ? lead.getTcBillFileName() : "bill";
            return org.springframework.http.ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + fileName + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType(mime))
                    .body(lead.getTcBillFileData());
        } catch (CustomException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void ensureTelecaller(String role) throws CustomException {
        if (!"TELECALLER".equalsIgnoreCase(role)) {
            throw new CustomException("Access denied: Telecaller role required");
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String msg) {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", msg);
        return ResponseEntity.status(status).body(err);
    }
}