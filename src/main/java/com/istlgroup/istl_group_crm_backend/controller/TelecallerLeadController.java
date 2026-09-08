package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.customException.NotPermittedException;
import com.istlgroup.istl_group_crm_backend.service.TelecallerLeadService;
import com.istlgroup.istl_group_crm_backend.service.FollowupsService;
import com.istlgroup.istl_group_crm_backend.service.LeadsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TelecallerStatusUpdateRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadRequestWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restricted controller for Telecaller users.
 */
@RestController
@RequestMapping("/telecaller")
public class TelecallerLeadController {

    @Autowired private TelecallerLeadService telecallerLeadService;
    @Autowired private FollowupsService followupsService;
    // Reused as-is for creation: all assignment logic stays in the one place.
    @Autowired private LeadsService leadsService;

    // ── GET /telecaller/my-leads ──────────────────────────────────────────────
    @GetMapping("/my-leads")
    public ResponseEntity<Map<String, Object>> getMyLeads(
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
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

    // ── POST /telecaller/lead ─────────────────────────────────────────────────
    /**
     * Lets a telecaller record a lead straight off a phone call.
     *
     * Deliberately thin: it forces the fields that decide ownership and then
     * hands off to the one lead-creation service. With no explicit assignee and
     * a non-BD-owned status, {@code LeadsService.createLead} falls through to its
     * SELF_CREATOR branch, which assigns the lead to the telecaller who created
     * it. Clearing them server-side is what makes "a telecaller can only create
     * leads owned by themselves" a guarantee rather than a client convention —
     * an explicit assignedTo would route the lead elsewhere, and a BD-owned
     * status such as "Interested" would null out assignedTo and drop the lead
     * off the creator's own board.
     */
    @PostMapping("/lead")
    public ResponseEntity<Map<String, Object>> createLead(
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
            @RequestBody    LeadRequestWrapper req) {
        try {
            ensureTelecaller(userRole);

            if (isBlank(req.getName()))
                throw new CustomException("Lead name is required");
            // Downstream routing (round-robin, proposals) keys off these.
            if (isBlank(req.getGroupName()) || isBlank(req.getSubGroupName()))
                throw new CustomException("Group and category are required");

            // A telecaller has no privilege to direct a lead anywhere but at themselves.
            req.setAssignedTo(null);
            req.setAssignedToEmail(null);
            req.setClosedByUserId(null);
            req.setClosedByName(null);
            req.setLeadOwner(null);
            req.setStatus("New");

            var created = leadsService.createLead(req, userId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead created successfully");
            response.put("data",    created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (NotPermittedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── POST /telecaller/leads/bulk ───────────────────────────────────────────
    /**
     * Spreadsheet import. Same sanitising as the single create, applied per row,
     * then straight into the shared bulk service — so an imported lead lands on
     * the importing telecaller exactly like one they typed in, and an
     * "Assigned To" column in a hand-edited sheet cannot route leads elsewhere.
     */
    @PostMapping("/leads/bulk")
    public ResponseEntity<Map<String, Object>> bulkCreateLeads(
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
            @RequestBody    List<LeadRequestWrapper> leads) {
        try {
            ensureTelecaller(userRole);
            if (leads == null || leads.isEmpty())
                throw new CustomException("No rows to import");

            for (LeadRequestWrapper req : leads) {
                req.setAssignedTo(null);
                req.setAssignedToEmail(null);
                req.setClosedByUserId(null);
                req.setClosedByName(null);
                req.setLeadOwner(null);
                req.setStatus("New");
            }

            Map<String, Object> result = leadsService.bulkCreateLeads(leads, userId);
            Map<String, Object> response = new HashMap<>(result);
            response.put("success", true);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (NotPermittedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── GET /telecaller/lead/{leadId} ─────────────────────────────────────────
    @GetMapping("/lead/{leadId}")
    public ResponseEntity<Map<String, Object>> getLeadDetail(
            @PathVariable               Long leadId,
            @ActingUserId   Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
            @RequestBody                FollowupRequestWrapper req) {
        try {
            ensureTelecaller(userRole);
            // This path previously took the lead id straight off the URL without
            // checking it belonged to the caller — it only forced the assignee.
            // Now that the assignee is caller-supplied, the lead must be checked too.
            var lead = telecallerLeadService.requireOwnedLead(leadId, userId);
            req.setRelatedType("LEAD");
            req.setRelatedId(leadId);
            req.setLeadId(leadId);
            req.setGroupName(lead.getGroupName());
            req.setSubGroupName(lead.getSubGroupName());
            // Self, a marketing executive or a BD executive — nobody else.
            req.setAssignedTo(telecallerLeadService.resolveFollowupAssignee(req.getAssignedTo(), userId));
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

    // ── GET /telecaller/lead/{leadId}/followups ───────────────────────────────
    /**
     * Follow-ups on one of the caller's own leads. Wraps the generic
     * {@code /followups/lead/{id}} read, which has no ownership check of its own,
     * behind the same rule the rest of this controller applies.
     */
    @GetMapping("/lead/{leadId}/followups")
    public ResponseEntity<Map<String, Object>> getLeadFollowups(
            @PathVariable               Long leadId,
            @ActingUserId   Long userId,
            @ActingUserRole String userRole) {
        try {
            ensureTelecaller(userRole);
            telecallerLeadService.requireOwnedLead(leadId, userId);
            var followups = followupsService.getFollowupsForLead(leadId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    followups);
            response.put("count",   followups.size());
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── PUT /telecaller/lead/{leadId}/followup/{followupId} ───────────────────
    /**
     * Completes, reschedules or cancels a follow-up on one of the caller's own
     * leads. The generic {@code /followups/update/{id}} has no ownership check,
     * so this wraps it with two: the lead is the caller's, and the follow-up
     * really belongs to that lead.
     */
    @PutMapping("/lead/{leadId}/followup/{followupId}")
    public ResponseEntity<Map<String, Object>> updateLeadFollowup(
            @PathVariable               Long leadId,
            @PathVariable               Long followupId,
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
            @RequestBody FollowupRequestWrapper req) {
        try {
            ensureTelecaller(userRole);
            telecallerLeadService.requireOwnedLead(leadId, userId);
            telecallerLeadService.requireFollowupOnLead(followupId, leadId);
            // A reassignment through this path answers to the same whitelist as a create.
            if (req.getAssignedTo() != null)
                req.setAssignedTo(telecallerLeadService.resolveFollowupAssignee(req.getAssignedTo(), userId));
            var result = followupsService.updateFollowup(followupId, req, userId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Follow-up updated");
            response.put("data",    result);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── GET /telecaller/followup-assignees ────────────────────────────────────
    /** Who this telecaller may hand a follow-up to: themselves, marketing or BD. */
    @GetMapping("/followup-assignees")
    public ResponseEntity<Map<String, Object>> getFollowupAssignees(
            @ActingUserId   Long userId,
            @ActingUserRole String userRole) {
        try {
            ensureTelecaller(userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data",    telecallerLeadService.getFollowupAssignees(userId));
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── POST /telecaller/lead/{leadId}/upload-bill ────────────────────────────
    @PostMapping(value = "/lead/{leadId}/upload-bill", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBillFile(
            @PathVariable               Long leadId,
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId   Long userId,
            @ActingUserRole String userRole) {
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String msg) {
        Map<String, Object> err = new HashMap<>();
        err.put("success", false);
        err.put("message", msg);
        return ResponseEntity.status(status).body(err);
    }
}