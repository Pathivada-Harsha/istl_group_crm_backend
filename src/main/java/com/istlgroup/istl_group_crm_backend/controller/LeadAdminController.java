package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.LeadAdminService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateBomLinesRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeLinesRequest;

import lombok.RequiredArgsConstructor;

/**
 * Admin CRUD for lead scope/BOM templates. Follows DropdownAdminController's
 * page=-1 sentinel (full list for form selects vs paged table). Gated in the UI
 * by the OFFICE_USE menu permission; no per-page server gate (same as the other
 * /admin surfaces).
 */
@RestController
@RequestMapping("/admin/lead-templates")
@RequiredArgsConstructor
public class LeadAdminController {

    private final LeadAdminService service;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "page",   required = false, defaultValue = "-1") int page,
            @RequestParam(value = "size",   required = false, defaultValue = "10") int size,
            @RequestParam(value = "search", required = false, defaultValue = "")   String search) {
        if (page < 0) return ResponseEntity.ok(service.listTemplates());
        return ResponseEntity.ok(service.listTemplatesPaged(search, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getTemplate(id));
        } catch (CustomException e) {
            return err(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@ActingUserId Long userId,
                                    @RequestBody TemplateHeaderRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.createTemplate(body, userId));
        } catch (CustomException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TemplateHeaderRequest body) {
        try {
            return ResponseEntity.ok(service.updateTemplate(id, body));
        } catch (CustomException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.deleteTemplate(id);
            return ResponseEntity.noContent().build();
        } catch (CustomException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{id}/scope-items")
    public ResponseEntity<?> saveScopeLines(@PathVariable Long id,
                                            @ActingUserId Long userId,
                                            @RequestBody TemplateScopeLinesRequest body) {
        try {
            return ResponseEntity.ok(service.saveScopeLines(id, body, userId));
        } catch (CustomException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{id}/bom-items")
    public ResponseEntity<?> saveBomLines(@PathVariable Long id,
                                          @ActingUserId Long userId,
                                          @RequestBody TemplateBomLinesRequest body) {
        try {
            return ResponseEntity.ok(service.saveBomLines(id, body, userId));
        } catch (CustomException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> err(HttpStatus status, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }
}
