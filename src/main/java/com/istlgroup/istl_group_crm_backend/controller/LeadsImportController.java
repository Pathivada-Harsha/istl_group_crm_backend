package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.LeadsImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * POST /leads/import  — accepts .xlsx file, parses rows, bulk-creates leads
 *                       with round-robin telecaller assignment.
 */
@RestController
@RequestMapping("/leads")
public class LeadsImportController {

    @Autowired
    private LeadsImportService leadsImportService;

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importLeads(
            @RequestHeader("User-Id")   Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam("file")       MultipartFile file) {

        Map<String, Object> response = new HashMap<>();
        try {
            if (file.isEmpty())
                throw new IllegalArgumentException("Uploaded file is empty");

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".xlsx"))
                throw new IllegalArgumentException("Only .xlsx files are accepted");

            var result = leadsImportService.importFromExcel(file, userId, userRole);

            response.put("success",   true);
            response.put("imported",  result.getImportedCount());
            response.put("skipped",   result.getSkippedCount());
            response.put("errors",    result.getRowErrors());
            response.put("message",
                String.format("Import complete: %d leads created, %d skipped.",
                              result.getImportedCount(), result.getSkippedCount()));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Import failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}