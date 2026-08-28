package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.TeamService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TeamRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TeamResponseWrapper;

@RestController
@RequestMapping("/teams")
public class TeamController {

    @Autowired
    private TeamService teamService;

    // ── GET /teams/all ────────────────────────────────────────────────────────
    @GetMapping("/all")
    public ResponseEntity<?> getAllTeams() {
        try {
            List<TeamResponseWrapper> teams = teamService.getAllTeams();
            return ResponseEntity.ok(teams);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── GET /teams/{id} ───────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<?> getTeam(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(teamService.getTeamById(id));
        } catch (CustomException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── POST /teams/create ────────────────────────────────────────────────────
    @PostMapping("/create")
    public ResponseEntity<?> createTeam(
            @ActingUserId Long userId,
            @RequestBody TeamRequestWrapper request) {
        try {
            TeamResponseWrapper created = teamService.createTeam(request, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── PUT /teams/{id} ───────────────────────────────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<?> updateTeam(
            @PathVariable Long id,
            @RequestBody TeamRequestWrapper request) {
        try {
            TeamResponseWrapper updated = teamService.updateTeam(id, request);
            return ResponseEntity.ok(updated);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── DELETE /teams/{id} ────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTeam(@PathVariable Long id) {
        try {
            teamService.deleteTeam(id);
            return ResponseEntity.ok(Map.of("message", "Team deleted successfully"));
        } catch (CustomException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        Map<String, String> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}