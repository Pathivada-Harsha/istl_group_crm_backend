package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.service.TaskService;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * GET /tasks?userId=&search=&status=&priority=&category=&dateFrom=&dateTo=&page=1&size=15
 * GET /tasks?teamView=true&userId=&search=&dateFrom=&dateTo=&page=1&size=15
 */
@RestController
@RequestMapping("/tasks")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class TaskController {

    @Autowired private TaskService taskService;
    @Autowired private RoleHierarchyRepo roleHierarchyRepo;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTasks(
            @RequestParam(value = "userId",   required = false) String userIdParam,
            @RequestParam(value = "search",   required = false, defaultValue = "") String search,
            @RequestParam(value = "status",   required = false, defaultValue = "") String status,
            @RequestParam(value = "priority", required = false, defaultValue = "") String priority,
            @RequestParam(value = "category", required = false, defaultValue = "") String category,
            @RequestParam(value = "dateFrom", required = false) String dateFromParam,
            @RequestParam(value = "dateTo",   required = false) String dateToParam,
            @RequestParam(value = "sortBy",   required = false, defaultValue = "dueDate") String sortBy,
            @RequestParam(value = "sortDir",  required = false, defaultValue = "desc")   String sortDir,
            @RequestParam(value = "teamView", required = false, defaultValue = "false") boolean teamView,
            @RequestParam(value = "page",     required = false, defaultValue = "1")  int page,
            @RequestParam(value = "size",     required = false, defaultValue = "15") int size,
            @ActingUserId String userIdHeader,
            @ActingUserRole String userRole) {

        try {
            Long requesterId = parseId(userIdHeader);
            if (requesterId == null) return unauthorized();

            boolean isSA = "SUPERADMIN".equals(userRole) || "ADMIN".equals(userRole);
            int roleLevel = roleHierarchyRepo.findByRoleName(
                userRole != null ? userRole.toUpperCase() : "")
                .map(r -> r.getLevelOrder() != null ? r.getLevelOrder() : 99)
                .orElse(99);
            boolean isManager = !isSA && roleLevel == 3;
            Long filterUserId = parseId(userIdParam);

            Long resolvedUserId;
            if      (isSA && filterUserId == null) resolvedUserId = null;
            else if (filterUserId != null)          resolvedUserId = filterUserId;
            else                                    resolvedUserId = requesterId;

            java.time.LocalDate dateFrom = parseDate(dateFromParam);
            java.time.LocalDate dateTo   = parseDate(dateToParam);
            org.springframework.data.domain.Sort sort = buildSort(sortBy, sortDir);

            Map<String, Object> result;
            if      (teamView && isSA)      result = taskService.getTasksForTeamView(resolvedUserId, dateFrom, dateTo, search, page, size, sort);
            else if (teamView && isManager) result = taskService.getTasksForManagerView(requesterId, filterUserId, dateFrom, dateTo, search, page, size, sort);
            else                            result = taskService.getTasks(resolvedUserId, userRole, search, status, priority, category, dateFrom, dateTo, page, size, sort);

            return ResponseEntity.ok(result);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTask(
            @PathVariable Long id,
            @ActingUserId String userIdHeader,
            @ActingUserRole String userRole) {
        try {
            if (parseId(userIdHeader) == null) return unauthorized();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("data", taskService.getTaskDetail(id));
            return ResponseEntity.ok(resp);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(
            @RequestBody Map<String, Object> body,
            @ActingUserId String userIdHeader,
            @ActingUserRole String userRole) {
        try {
            Long userId = parseId(userIdHeader);
            if (userId == null) return unauthorized();
            String name = (String) body.getOrDefault("createdByName", "User #" + userId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Task created");
            resp.put("data", taskService.createTask(body, userId, name));
            return ResponseEntity.ok(resp);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTask(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @ActingUserId String userIdHeader,
            @ActingUserRole String userRole) {
        try {
            Long userId = parseId(userIdHeader);
            if (userId == null) return unauthorized();
            // Pass updatedBy details so TaskService can fire reassignment mail if needed
            String name = (String) body.getOrDefault("updatedByName",
                    body.getOrDefault("createdByName", "User #" + userId));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Task updated");
            resp.put("data", taskService.updateTask(id, body, userId, name));
            return ResponseEntity.ok(resp);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    @PostMapping("/{id}/update")
    public ResponseEntity<Map<String, Object>> logUpdate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @ActingUserId String userIdHeader,
            @ActingUserRole String userRole) {
        try {
            Long userId = parseId(userIdHeader);
            if (userId == null) return unauthorized();
            String name = (String) body.getOrDefault("updatedByName", "User #" + userId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Update logged");
            resp.put("data", taskService.logDailyUpdate(id, body, userId, name));
            return ResponseEntity.ok(resp);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTask(
            @PathVariable Long id,
            @ActingUserId String userIdHeader,
            @ActingUserRole String userRole) {
        try {
            Long userId = parseId(userIdHeader);
            if (userId == null) return unauthorized();
            if (!"SUPERADMIN".equals(userRole) && !"ADMIN".equals(userRole)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("success", false);
                r.put("message", "Only SuperAdmin/Admin can delete tasks");
                return ResponseEntity.status(403).body(r);
            }
            taskService.deleteTask(id);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Task deleted");
            return ResponseEntity.ok(resp);
        } catch (Exception e) { return error(e.getMessage()); }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long parseId(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return null; }
    }

    private org.springframework.data.domain.Sort buildSort(String sortBy, String sortDir) {
        String field = (sortBy != null && !sortBy.isBlank()) ? sortBy : "dueDate";
        org.springframework.data.domain.Sort.Direction dir =
            "asc".equalsIgnoreCase(sortDir)
                ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC;
        return org.springframework.data.domain.Sort.by(dir, field);
    }

    private java.time.LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) return null;
        try { return java.time.LocalDate.parse(v.trim()); } catch (Exception e) { return null; }
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false); r.put("message", "Unauthorized");
        return ResponseEntity.status(401).body(r);
    }

    private ResponseEntity<Map<String, Object>> error(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false); r.put("message", msg != null ? msg : "Internal error");
        return ResponseEntity.status(500).body(r);
    }
}