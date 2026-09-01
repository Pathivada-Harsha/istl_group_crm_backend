package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ImportResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Parses uploaded Excel templates and bulk-creates leads.
 *
 * ── Duplicate detection (layered defence) ─────────────────────────────────────
 * Layer 1 — In-memory set:
 *   All existing non-deleted phone numbers are loaded from DB at import start
 *   (normalised to 10 digits). Rows whose phone matches any existing DB phone,
 *   OR any earlier row in the same file, are skipped immediately.
 *
 * Layer 2 — DB unique constraint (uq_leads_phone_active):
 *   If two concurrent import requests race past Layer 1 with the same phone,
 *   the DB constraint fires a DataIntegrityViolationException. This is caught
 *   per-row and reported as a duplicate rather than crashing the whole import.
 *
 * ── Lead code generation ──────────────────────────────────────────────────────
 * Lead codes are assigned AFTER save using the auto-incremented DB id.
 * Format: LEAD-{YEAR}-{id:06d}
 * This avoids the race condition where multiple rows in the same transaction
 * all saw the same COUNT(*) and got assigned the same code.
 *
 * ── Transactional scope ──────────────────────────────────────────────────────
 * Each row is saved in its own try/catch. The method is NOT @Transactional at
 * the method level — this means a failure on row 50 does NOT roll back rows 1-49.
 * Partial imports are intentional: the caller sees exactly which rows succeeded
 * and which were skipped, and can re-upload a corrected file for the failures.
 */
@Service
@Slf4j
public class LeadsImportService {

    private static final String SHEET_STANDARD  = "Leads Import";
    private static final String SHEET_PM_SURYA  = "PM Surya Ghar Import";
    private static final int    DATA_START_STD  = 3;
    private static final int    DATA_START_PM   = 4;
    private static final int    MAX_ROWS         = 500;
    private static final String PM_CATEGORY     = "Solar_Rooftop";
    private static final String PM_SCHEME       = "PM_Surya_Ghar";
    private static final String PM_DEFAULT_GROUP = "EPC";

    @Autowired private LeadsRepo                   leadsRepo;
    @Autowired private UsersRepo                   usersRepo;
    @Autowired private RoundRobinAssignmentService roundRobin;
    @Autowired private LeadHistoryService          leadHistoryService;
    @Autowired private MailService                 mailService;

    // NOTE: No @Transactional here intentionally — each row commits independently
    // so a bad row does not roll back successfully imported rows before it.
    public ImportResult importFromExcel(MultipartFile file, Long importedBy, String userRole)
            throws Exception {

        ImportResult result = new ImportResult();

        // ── Layer 1: pre-load all existing normalised phones from DB ───────────
        Set<String> existingPhones = new HashSet<>();
        leadsRepo.findAllActivePhones().forEach(p -> {
            String norm = normalisePhone(p);
            if (norm != null) existingPhones.add(norm);
        });

        Map<String, Long> emailToUserId = new HashMap<>();

        // Collects successfully saved leads grouped by assignedTo telecaller ID.
        // After import completes, one summary email is sent per telecaller.
        Map<Long, List<LeadsEntity>> telecallerAssignments = new HashMap<>();

        try (InputStream is = file.getInputStream();
             Workbook wb    = new XSSFWorkbook(is)) {

            boolean isPM       = wb.getSheet(SHEET_PM_SURYA) != null;
            boolean isStandard = wb.getSheet(SHEET_STANDARD) != null;

            if (!isPM && !isStandard) {
                throw new IllegalArgumentException(
                    "Neither sheet '" + SHEET_STANDARD + "' nor '" + SHEET_PM_SURYA +
                    "' was found. Use the official import template.");
            }

            if (isPM) {
                processSheet(wb.getSheet(SHEET_PM_SURYA), true,
                             DATA_START_PM, result, existingPhones, emailToUserId, importedBy,
                             telecallerAssignments);
            } else {
                processSheet(wb.getSheet(SHEET_STANDARD), false,
                             DATA_START_STD, result, existingPhones, emailToUserId, importedBy,
                             telecallerAssignments);
            }
        }

        // ── Send one summary email per telecaller for all their assigned leads ─
        for (Map.Entry<Long, List<LeadsEntity>> entry : telecallerAssignments.entrySet()) {
            Long telecallerId = entry.getKey();
            List<LeadsEntity> assignedLeads = entry.getValue();
            try {
                usersRepo.findById(telecallerId).ifPresent(telecaller -> {
                    if (telecaller.getEmail() != null && !telecaller.getEmail().isBlank()) {
                        int count = assignedLeads.size();
                        String subject = count == 1
                            ? "New Lead Assigned to You – " + assignedLeads.get(0).getLeadCode()
                            : count + " New Leads Assigned to You";
                        String body = buildBulkLeadEmailBody(telecaller.getName(), assignedLeads);
                        mailService.sendEmail(telecaller.getEmail(), subject, body);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to send import assignment email to telecaller {}: {}", telecallerId, e.getMessage());
            }
        }

        return result;
    }

    // ── Sheet processor ───────────────────────────────────────────────────────

    private void processSheet(Sheet sheet, boolean isPM, int dataStart,
                              ImportResult result, Set<String> existingPhones,
                              Map<String, Long> emailToUserId, Long importedBy,
                              Map<Long, List<LeadsEntity>> telecallerAssignments) {

        int lastRow = Math.min(sheet.getLastRowNum(), dataStart + MAX_ROWS - 1);

        for (int r = dataStart; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row, isPM ? 12 : 15)) continue;

            int displayRow = r + 1;
            try {
                LeadsEntity lead = isPM
                    ? buildPMSuryaLead(row, displayRow, result, existingPhones, emailToUserId, importedBy)
                    : buildStandardLead(row, displayRow, result, existingPhones, emailToUserId, importedBy);

                if (lead == null) continue; // validation or Layer-1 duplicate — already counted

                // ── Save and assign lead code using the generated DB id ────────
                LeadsEntity saved;
                try {
                    saved = leadsRepo.save(lead);
                    // Assign lead code based on DB-generated id — no race condition
                    saved.setLeadCode(buildLeadCode(saved.getId()));
                    saved = leadsRepo.save(saved);
                } catch (DataIntegrityViolationException | org.springframework.orm.jpa.JpaSystemException dive) {
                    // ── Layer 2: DB unique constraint on phone fired ───────────
                    // Catches error 1062 - duplicate entry for uq_leads_phone_active.
                    // This fires when a concurrent import/API call inserted the same
                    // phone between our Layer-1 check and this save.
                    String phone = lead.getPhone() != null ? lead.getPhone() : "unknown";
                    result.addError(displayRow,
                        "Duplicate — phone " + phone + " already exists; row skipped");
                    result.incrementSkipped();
                    log.warn("Duplicate phone {} blocked by DB constraint at import row {}", phone, displayRow);
                    continue;
                } catch (Exception saveEx) {
                    // Catch any other persistence exception that wraps a constraint violation
                    String msg = saveEx.getMessage() != null ? saveEx.getMessage() : "";
                    if (msg.contains("1062") || msg.contains("Duplicate entry") || msg.contains("uq_leads_phone")) {
                        String phone = lead.getPhone() != null ? lead.getPhone() : "unknown";
                        result.addError(displayRow,
                            "Duplicate — phone " + phone + " already exists; row skipped");
                        result.incrementSkipped();
                        log.warn("Duplicate phone {} (wrapped exception) at import row {}", phone, displayRow);
                        continue;
                    }
                    throw saveEx; // re-throw non-duplicate errors
                }

                // Add this phone to the in-memory set so later rows in the same
                // file are caught by Layer 1 (fast path, no DB round-trip needed)
                String normPhone = normalisePhone(saved.getPhone());
                if (normPhone != null) existingPhones.add(normPhone);

                // ── Track per-telecaller assignments for batch email ──────────
                if (saved.getAssignedTo() != null) {
                    telecallerAssignments
                        .computeIfAbsent(saved.getAssignedTo(), k -> new java.util.ArrayList<>())
                        .add(saved);
                }

                // History
                try {
                    leadHistoryService.addHistory(saved.getId(), "CREATED",
                        null, null, null, "Lead created via Excel import", importedBy);
                    if (saved.getAssignedTo() != null) {
                        String assignedName = usersRepo.findById(saved.getAssignedTo())
                            .map(u -> u.getName()).orElse("Unknown");
                        // Deliberately does NOT claim round-robin. Since resolveAssignment
                        // now rejects an unresolvable email instead of silently rotating,
                        // an assignment here came either from the sheet's "Assigned To
                        // (Email)" cell or from round-robin on an empty cell — and this
                        // dead-endpoint path does not thread that distinction through to
                        // here. Saying "via round-robin" was wrong whenever the sheet
                        // named someone, which is the case this whole change is about.
                        leadHistoryService.addHistory(saved.getId(), "ASSIGNED",
                            "assignedTo", "Unassigned", assignedName,
                            "Assigned on import", importedBy);
                    }
                } catch (Exception ex) {
                    log.warn("History write failed for imported lead {}: {}", saved.getId(), ex.getMessage());
                }

                result.incrementImported();

            } catch (Exception e) {
                result.addError(displayRow, "Unexpected error: " + e.getMessage());
                result.incrementSkipped();
                log.error("Import error at row {}: {}", displayRow, e.getMessage(), e);
            }
        }
    }

    // ── Standard template ─────────────────────────────────────────────────────

    private LeadsEntity buildStandardLead(Row row, int displayRow, ImportResult result,
                                          Set<String> existingPhones,
                                          Map<String, Long> emailToUserId, Long importedBy) {

        String name         = getString(row, 0);
        String email        = getString(row, 1);
        String rawPhone     = getString(row, 2);
        String source       = getString(row, 3);
        String priority     = getString(row, 4);
        String group        = getString(row, 5);
        String subGroup     = getString(row, 6);
        String enquiry      = getString(row, 7);
        String state        = getString(row, 8);
        String district     = getString(row, 9);
        String city         = getString(row, 10);
        String pincode      = getString(row, 11);
        String scheme       = getString(row, 12);
        String assignedEmail= getString(row, 13);
        String notes        = getString(row, 14);

        // ── Normalise & validate phone ─────────────────────────────────────────
        String phone = normalisePhone(rawPhone);

        List<String> errs = new ArrayList<>();
        if (name.isBlank())        errs.add("Client Name is required");
        if (rawPhone.isBlank())    errs.add("Phone is required");
        else if (phone == null)    errs.add("Phone must be exactly 10 digits (got: " + rawPhone.replaceAll("[^\\d]", "") + ")");
        if (enquiry.isBlank())     errs.add("Enquiry Description is required");
        if (state.isBlank())       errs.add("State is required");
        if (!email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            errs.add("Invalid email format");

        if (!errs.isEmpty()) {
            result.addError(displayRow, String.join("; ", errs));
            result.incrementSkipped();
            return null;
        }

        // ── Layer 1 duplicate check ────────────────────────────────────────────
        // phone is non-null here (validated above)
        if (existingPhones.contains(phone)) {
            result.addError(displayRow,
                "Duplicate — phone " + phone + " already exists (DB or earlier in this file)");
            result.incrementSkipped();
            return null;
        }

        if (source.isBlank())   source   = "Others";
        if (priority.isBlank()) priority = "Medium";

        Long assignedTo;
        try {
            assignedTo = resolveAssignment(assignedEmail, group, subGroup, emailToUserId, result, displayRow);
        } catch (UnresolvableAssigneeException e) {
            result.addError(displayRow, e.getMessage());
            result.incrementSkipped();
            return null;
        }

        LeadsEntity lead = new LeadsEntity();
        // leadCode is intentionally NOT set here — it's assigned after save using the DB id
        lead.setName(name);
        lead.setEmail(email.isBlank() ? null : email.toLowerCase());
        lead.setPhone(phone);
        lead.setSource(source);
        lead.setPriority(priority);
        lead.setStatus("New");
        lead.setGroupName(blankToNull(group));
        lead.setSubGroupName(blankToNull(subGroup));
        lead.setSolarScheme(blankToNull(scheme));
        lead.setState(blankToNull(state));
        lead.setDistrict(blankToNull(district));
        lead.setCity(blankToNull(city));
        lead.setPincode(blankToNull(pincode));
        lead.setEnquiry(enquiry + (notes.isBlank() ? "" : "\n\nNotes: " + notes));
        lead.setAssignedTo(assignedTo);
        lead.setCreatedBy(importedBy);
        return lead;
    }

    // ── PM Surya Ghar template ────────────────────────────────────────────────

    private LeadsEntity buildPMSuryaLead(Row row, int displayRow, ImportResult result,
                                         Set<String> existingPhones,
                                         Map<String, Long> emailToUserId, Long importedBy) {

        String groupFromSheet = getString(row, 0);
        String group          = groupFromSheet.isBlank() ? PM_DEFAULT_GROUP : groupFromSheet.trim();
        String rawName        = getString(row, 1);
        String email          = getString(row, 2);
        String rawPhone       = getString(row, 3);
        String source         = getString(row, 4);
        String priority       = getString(row, 5);
        String enquiry        = getString(row, 6);
        String state          = getString(row, 7);
        String district       = getString(row, 8);
        String city           = getString(row, 9);
        String assignedEmail  = getString(row, 10);
        String notes          = getString(row, 11);

        // ── Normalise & validate phone ─────────────────────────────────────────
        String phone = normalisePhone(rawPhone);
        String name  = rawName.isBlank() ? (phone != null ? phone : rawPhone) : rawName;

        List<String> errs = new ArrayList<>();
        if (rawPhone.isBlank())  errs.add("Phone is required");
        else if (phone == null)  errs.add("Phone must be exactly 10 digits (got: " + rawPhone.replaceAll("[^\\d]", "") + ")");
        if (!email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            errs.add("Invalid email format");

        if (!errs.isEmpty()) {
            result.addError(displayRow, String.join("; ", errs));
            result.incrementSkipped();
            return null;
        }

        // ── Layer 1 duplicate check ────────────────────────────────────────────
        if (existingPhones.contains(phone)) {
            result.addError(displayRow,
                "Duplicate — phone " + phone + " already exists (DB or earlier in this file)");
            result.incrementSkipped();
            return null;
        }

        if (source.isBlank())   source   = "Others";
        if (priority.isBlank()) priority = "Medium";

        Long assignedTo;
        try {
            assignedTo = resolveAssignment(assignedEmail, group, PM_CATEGORY, emailToUserId, result, displayRow);
        } catch (UnresolvableAssigneeException e) {
            result.addError(displayRow, e.getMessage());
            result.incrementSkipped();
            return null;
        }

        String fullEnquiry = enquiry.isBlank()
            ? (notes.isBlank() ? "" : notes)
            : enquiry + (notes.isBlank() ? "" : "\n\nNotes: " + notes);

        LeadsEntity lead = new LeadsEntity();
        // leadCode assigned after save
        lead.setName(name);
        lead.setEmail(email.isBlank() ? null : email.toLowerCase());
        lead.setPhone(phone);
        lead.setSource(source);
        lead.setPriority(priority);
        lead.setStatus("New");
        lead.setGroupName(group);
        lead.setSubGroupName(PM_CATEGORY);
        lead.setSolarScheme(PM_SCHEME);
        lead.setState(blankToNull(state));
        lead.setDistrict(blankToNull(district));
        lead.setCity(blankToNull(city));
        lead.setPincode(null);
        lead.setEnquiry(fullEnquiry.isBlank() ? null : fullEnquiry);
        lead.setAssignedTo(assignedTo);
        lead.setCreatedBy(importedBy);
        return lead;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Normalises a phone string to exactly 10 digits.
     * Strips all non-digit characters, then takes the last 10 digits
     * (handles +91 prefix, country codes, spaces, dashes, etc.)
     * Returns null if fewer than 10 digits remain after stripping.
     */
    private String normalisePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^\\d]", "");
        if (digits.length() < 10) return null;
        // Take last 10 digits — strips country code prefix e.g. +91
        String phone = digits.substring(digits.length() - 10);
        // Must be exactly 10 digits and not all zeros
        if (phone.length() != 10 || phone.equals("0000000000")) return null;
        return phone;
    }

    /**
     * Builds a lead code from the DB-assigned id.
     * Format: LEAD-{YEAR}-{id:06d}
     * Using the id (not a COUNT) guarantees uniqueness even under concurrent imports.
     */
    private String buildLeadCode(Long id) {
        String year = String.valueOf(LocalDateTime.now().getYear());
        return String.format("LEAD-%s-%06d", year, id);
    }

    /**
     * Signals that a row named an assignee that cannot be honoured. The caller turns this
     * into a skipped row, matching how validation failures are already reported.
     */
    private static class UnresolvableAssigneeException extends RuntimeException {
        UnresolvableAssigneeException(String message) { super(message); }
    }

    /**
     * Resolves the sheet's "Assigned To (Email)" cell, falling back to the telecaller
     * round-robin only when the cell is EMPTY.
     *
     * <p>An email that does not resolve rejects the row. It used to log a warning and
     * round-robin the lead anyway, which defeats the point of the column: the importer
     * deliberately named an owner and the lead went to somebody else, with the warning
     * buried in a list the uploader had no reason to read. Rejecting makes the typo
     * visible and keeps the round-robin rotation out of it.
     *
     * <p>Kept in step with {@code LeadsService.resolveAssignedToEmail}, which does the same
     * job for the live {@code /leads/bulk-create} path. Two importers, one rule.
     */
    private Long resolveAssignment(String assignedEmail, String group, String subGroup,
                                   Map<String, Long> emailToUserId,
                                   ImportResult result, int displayRow) {
        if (assignedEmail.isBlank()) {
            // No owner named — this is the case round-robin exists for.
            return roundRobin.getNextTelecaller(group, subGroup);
        }

        String email = assignedEmail.replace(' ', ' ').trim();
        Long assignedTo = emailToUserId.computeIfAbsent(
            email.toLowerCase(),
            e -> usersRepo.findByEmailIgnoreCase(e).stream()
                    .filter(u -> u.getIs_active() != null && u.getIs_active() == 1L)
                    .map(u -> u.getId())
                    .findFirst()
                    .orElse(null));

        if (assignedTo == null) {
            throw new UnresolvableAssigneeException(
                "Assigned To email '" + email + "' does not match any active CRM user");
        }
        return assignedTo;
    }

    private String getString(Row row, int colIdx) {
        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            // Cast to long to avoid "9876543210.0" from numeric cells
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default      -> "";
        };
    }

    private boolean isRowEmpty(Row row, int checkCols) {
        for (int c = 0; c < checkCols; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK && !getString(row, c).isBlank())
                return false;
        }
        return true;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Builds an HTML email body listing all leads assigned to one telecaller.
     * Used after bulk import to send a single summary email instead of one per lead.
     */
    private String buildBulkLeadEmailBody(String telecallerName, List<LeadsEntity> leads) {
        StringBuilder rows = new StringBuilder();
        int index = 1;
        for (LeadsEntity lead : leads) {
            String bg = (index % 2 == 0) ? "#f9f9f9" : "#ffffff";
            rows.append("<tr style='background:").append(bg).append("'>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(index).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getLeadCode())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getName())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getPhone())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getState())).append("</td>")
                .append("<td style='padding:8px 10px;border-bottom:1px solid #e0e0e0'>").append(escapeHtml(lead.getPriority())).append("</td>")
                .append("</tr>");
            index++;
        }

        int count = leads.size();
        return "<div style='font-family:Arial,sans-serif;max-width:700px;margin:auto;border:1px solid #e0e0e0;border-radius:8px;overflow:hidden'>"
            + "<div style='background:#1a73e8;padding:20px 24px'>"
            + "<h2 style='color:#fff;margin:0;font-size:20px'>" + count + " New Lead" + (count > 1 ? "s" : "") + " Assigned to You</h2>"
            + "</div>"
            + "<div style='padding:24px'>"
            + "<p style='margin:0 0 16px'>Hi <strong>" + escapeHtml(telecallerName) + "</strong>,</p>"
            + "<p style='margin:0 0 16px'><strong>" + count + "</strong> new lead" + (count > 1 ? "s have" : " has") + " been assigned to you via bulk import:</p>"
            + "<table style='width:100%;border-collapse:collapse;font-size:13px'>"
            + "<thead><tr style='background:#1a73e8;color:#fff'>"
            + "<th style='padding:10px;text-align:left'>#</th>"
            + "<th style='padding:10px;text-align:left'>Lead Code</th>"
            + "<th style='padding:10px;text-align:left'>Client Name</th>"
            + "<th style='padding:10px;text-align:left'>Phone</th>"
            + "<th style='padding:10px;text-align:left'>State</th>"
            + "<th style='padding:10px;text-align:left'>Priority</th>"
            + "</tr></thead>"
            + "<tbody>" + rows + "</tbody>"
            + "</table>"
            + "<p style='margin:20px 0 0;font-size:13px;color:#666'>Please log in to the CRM to view full details and take action on your leads.</p>"
            + "<p style='margin:12px 0 0;font-size:13px'>"
            + "<a href='https://crm.sesolaenergy.com/' "
            + "style='display:inline-block;background:#1a73e8;color:#fff;padding:10px 22px;"
            + "border-radius:6px;text-decoration:none;font-weight:bold;font-size:13px'>"
            + "Open CRM &rarr;</a></p>"
            + "</div>"
            + "<div style='background:#f5f5f5;padding:12px 24px;font-size:12px;color:#999'>SESOLA Group CRM &mdash; This is an automated notification.</div>"
            + "</div>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}