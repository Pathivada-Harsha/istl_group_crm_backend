package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.customException.NotPermittedException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadFilterRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadRequestWrapper;
import com.istlgroup.istl_group_crm_backend.service.LeadsService;

@RestController
@RequestMapping("/leads")
public class LeadsController {

    @Autowired
    private LeadsService leadsService;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/getAll
    //   Supports optional server-side pagination via ?page=0&size=10
    //   When page/size are omitted the full list is returned (backwards compat).
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllLeads(
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false, defaultValue = "-1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        try {
            Map<String, Object> response = new HashMap<>();

            if (page >= 0) {
                // Server-side paginated path — most-recently-updated first (same default
                // as /filter) so newly-changed leads surface at the top for every role.
                Pageable pageable = PageRequest.of(page, size,
                        Sort.by(Sort.Direction.DESC, "updatedAt")
                            .and(Sort.by(Sort.Direction.DESC, "createdAt")));
                Page<LeadWrapper> leadsPage = leadsService.getLeadsPaged(
                        userId, userRole, groupName, subGroupName, pageable);

                response.put("success", true);
                response.put("data", leadsPage.getContent());
                response.put("count", leadsPage.getTotalElements());
                response.put("totalPages", leadsPage.getTotalPages());
                response.put("currentPage", leadsPage.getNumber());
                response.put("pageSize", leadsPage.getSize());
            } else {
                // Non-paginated fallback (backwards compat for other callers)
                List<LeadWrapper> leads = leadsService.getAllLeads(
                        userId, userRole, groupName, subGroupName);
                response.put("success", true);
                response.put("data", leads);
                response.put("count", leads.size());
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /leads/filter
    //   Body: LeadFilterRequestWrapper (search + filters)
    //   Supports pagination via ?page=0&size=10
    //   If filterRequest.exportAll = true, returns ALL matching records (no pagination)
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/filter")
    public ResponseEntity<Map<String, Object>> getFilteredLeads(
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size,
            @RequestBody LeadFilterRequestWrapper filterRequest) {
        try {
            // Export-all: fetch up to 10,000 records matching current filters
            if (Boolean.TRUE.equals(filterRequest.getExportAll())) {
                Pageable allPageable = PageRequest.of(0, 10000);
                Page<LeadWrapper> allLeads = leadsService.getFilteredLeadsPaged(
                        userId, userRole, filterRequest, allPageable);
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", allLeads.getContent());
                response.put("count", allLeads.getTotalElements());
                response.put("totalPages", 1);
                response.put("currentPage", 0);
                response.put("pageSize", (int) allLeads.getTotalElements());
                return ResponseEntity.ok(response);
            }

            Pageable pageable;
            if (filterRequest.getSortBy() != null && !filterRequest.getSortBy().isBlank()) {
                Sort.Direction dir = "asc".equalsIgnoreCase(filterRequest.getSortDirection())
                        ? Sort.Direction.ASC : Sort.Direction.DESC;
                pageable = PageRequest.of(page, size, Sort.by(dir, filterRequest.getSortBy()));
            } else {
                // Default: most recently updated first, so leads whose status/details just
                // changed (e.g. just marked Interested) surface at the top instead of being
                // buried by creation date. Ties fall back to newest-created.
                pageable = PageRequest.of(page, size,
                        Sort.by(Sort.Direction.DESC, "updatedAt")
                            .and(Sort.by(Sort.Direction.DESC, "createdAt")));
            }
            Page<LeadWrapper> leadsPage = leadsService.getFilteredLeadsPaged(
                    userId, userRole, filterRequest, pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leadsPage.getContent());
            response.put("count", leadsPage.getTotalElements());
            response.put("totalPages", leadsPage.getTotalPages());
            response.put("currentPage", leadsPage.getNumber());
            response.put("pageSize", leadsPage.getSize());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/{leadId}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{leadId}")
    public ResponseEntity<Map<String, Object>> getLeadById(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            LeadWrapper lead = leadsService.getLeadById(leadId, userId, userRole);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", lead);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Internal server error");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/by-group-subgroup
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/by-group-subgroup")
    public ResponseEntity<Map<String, Object>> getLeadsByGroupAndSubGroup(
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName) {
        try {
            List<LeadWrapper> leads = leadsService.getAllLeads(userId, userRole, groupName, subGroupName);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /leads/create
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createLead(
            @ActingUserId Long userId,
            @RequestBody LeadRequestWrapper requestDTO) {
        try {
            LeadWrapper createdLead = leadsService.createLead(requestDTO, userId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead created successfully");
            response.put("data", createdLead);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NotPermittedException e) {
            // Assign-To / Closed-By pointed outside the caller's reporting subtree.
            // MUST precede the generic Exception catch below, which would otherwise
            // bury a refused escalation in a 500.
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("error", "NOT_PERMITTED");
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            // Strip raw SQL / Hibernate details — show only a clean reason
            err.put("message", cleanError(e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /leads/bulk-create
    //   Accepts an array of lead payloads from the Excel import panel.
    //   Creates every lead silently (no per-lead email), then sends ONE summary
    //   email per telecaller covering all leads assigned to them in this batch.
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/bulk-create")
    public ResponseEntity<Map<String, Object>> bulkCreateLeads(
            @ActingUserId Long userId,
            @RequestBody List<LeadRequestWrapper> leads) {
        try {
            Map<String, Object> result = leadsService.bulkCreateLeads(leads, userId);
            result.put("success", true);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Bulk import failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    /** Strips raw Hibernate/JPA SQL so the frontend sees a clean error string. */
    private String cleanError(String raw) {
        if (raw == null) return "An unexpected error occurred";
        if (raw.contains("uq_leads_phone") || raw.contains("Duplicate entry")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Duplicate entry '(\\d+)'").matcher(raw);
            if (m.find()) return "Phone number " + m.group(1) + " already exists";
            return "Phone number already exists";
        }
        int cut = raw.indexOf(" [insert into");
        if (cut > 0) raw = raw.substring(0, cut).trim();
        cut = raw.indexOf(" [");
        if (cut > 0) raw = raw.substring(0, cut).trim();
        return raw;
    }

    // ─────────────────────────────────────────────────────────────────────────
    @PutMapping("/update/{leadId}")
    public ResponseEntity<Map<String, Object>> updateLead(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody LeadRequestWrapper requestDTO) {
        try {
            LeadWrapper updatedLead = leadsService.updateLead(leadId, requestDTO, userId, userRole);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead updated successfully");
            response.put("data", updatedLead);
            return ResponseEntity.ok(response);
        } catch (NotPermittedException e) {
            // Assign-To / Closed-By pointed outside the caller's reporting subtree.
            // MUST precede the generic Exception catch below.
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("error", "NOT_PERMITTED");
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Failed to update lead");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /leads/{leadId}/bd  — change or remove the BD executive on a lead.
    // Body: { "bdAssignedTo": <userId> }  (null / omitted → remove the BD)
    // ─────────────────────────────────────────────────────────────────────────
    @PutMapping("/{leadId}/bd")
    public ResponseEntity<Map<String, Object>> reassignBd(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody Map<String, Object> body) {
        try {
            Long newBdId = null;
            Object raw = body != null ? body.get("bdAssignedTo") : null;
            if (raw != null) {
                newBdId = Long.valueOf(String.valueOf(raw).trim());
            }

            LeadWrapper updatedLead = leadsService.reassignBd(leadId, newBdId, userId, userRole);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", newBdId == null ? "BD removed" : "BD updated");
            response.put("data", updatedLead);
            return ResponseEntity.ok(response);
        } catch (NumberFormatException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Invalid bdAssignedTo value");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Failed to update BD");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /leads/delete/{leadId}
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/delete/{leadId}")
    public ResponseEntity<Map<String, Object>> deleteLead(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            leadsService.deleteLead(leadId, userId, userRole);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead deleted successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Failed to delete lead");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/group/{groupName}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/group/{groupName}")
    public ResponseEntity<Map<String, Object>> getLeadsByGroup(
            @PathVariable String groupName,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsByGroup(groupName, userId, userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/status/{status}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/status/{status}")
    public ResponseEntity<Map<String, Object>> getLeadsByStatus(
            @PathVariable String status,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsByStatus(status, userId, userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/assigned/{assignedUserId}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/assigned/{assignedUserId}")
    public ResponseEntity<Map<String, Object>> getLeadsAssignedTo(
            @PathVariable Long assignedUserId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsAssignedTo(assignedUserId, userId, userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/createdBy/{createdByUserId}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/createdBy/{createdByUserId}")
    public ResponseEntity<Map<String, Object>> getLeadsCreatedBy(
            @PathVariable Long createdByUserId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsCreatedBy(createdByUserId, userId, userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /leads/{leadId}/upload-bill   (multipart, max 10 MB)
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping(value = "/{leadId}/upload-bill",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBillFile(
            @PathVariable               Long leadId,
            @ActingUserId   Long userId,
            @ActingUserRole String userRole,
            @RequestParam("file")       org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file.isEmpty()) throw new CustomException("No file provided");
            if (file.getSize() > 10L * 1024 * 1024)
                throw new CustomException("File size exceeds 10 MB limit");

            leadsService.saveBillFileToDb(leadId, userId, userRole, file);

            Map<String, Object> response = new HashMap<>();
            response.put("success",  true);
            response.put("message",  "Bill uploaded successfully");
            response.put("fileName", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /leads/{leadId}/bill   — stream the stored BLOB to client
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{leadId}/bill")
    public ResponseEntity<?> downloadBillFile(
            @PathVariable               Long leadId,
            @ActingUserId   Long userId,
            @ActingUserRole String userRole) {
        try {
            com.istlgroup.istl_group_crm_backend.entity.LeadsEntity lead =
                leadsService.getLeadEntityForBill(leadId, userId, userRole);

            String mime     = lead.getTcBillFileType() != null
                              ? lead.getTcBillFileType() : "application/octet-stream";
            String fileName = lead.getTcBillFileName() != null
                              ? lead.getTcBillFileName() : "bill";

            return org.springframework.http.ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + fileName + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType(mime))
                    .body(lead.getTcBillFileData());
        } catch (CustomException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }
}