package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.security.ActingUserName;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.TenderEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.service.TenderExcelService;
import com.istlgroup.istl_group_crm_backend.service.TenderService;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelOptions;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderWrapper;

import jakarta.servlet.http.HttpSession;

/**
 * Tenders REST API. Mirrors the OrderBook controller conventions: a
 * {@code {success, message, data}} envelope and the {@code User-Id} request
 * header for the current user. Protected by SessionFilter like every non-login
 * endpoint (no security config change needed).
 */
@RestController
@RequestMapping("/tender")
public class TenderController {

    @Autowired
    private TenderService tenderService;

    @Autowired
    private TenderExcelService tenderExcelService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAll() {
        try {
            List<TenderWrapper> data = tenderService.getAll();
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", data);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return error("Failed to load tenders: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        try {
            TenderWrapper data = tenderService.getById(id);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", data);
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return error("Failed to load tender: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody TenderWrapper request,
            @ActingUserId Long userId,
            @ActingUserName String userName) {
        try {
            TenderWrapper created = tenderService.create(request, userId, userName);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Tender created successfully");
            res.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to create tender: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody TenderWrapper request,
            @ActingUserId Long userId,
            @ActingUserName String userName) {
        try {
            TenderWrapper updated = tenderService.update(id, request, userId, userName);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Tender updated successfully");
            res.put("data", updated);
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update tender: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            tenderService.delete(id);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "Tender deleted successfully");
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return error("Failed to delete tender: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── source PDF: parse (stateless) / upload (store BLOB) / download (stream) ──

    /**
     * Stateless parse — no tender id, so it works for a brand-new unsaved
     * tender. Returns proposed values with the page and line each came from,
     * for the import review modal; nothing is written until the user applies.
     *
     * <p>{@code ai=true} re-reads the document with the LLM. It is always the
     * user's choice, and it is offered whether the parse came back incomplete
     * or came back looking plausible but wrong.
     */
    @PostMapping(value = "/parse-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> parsePdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ai", defaultValue = "false") boolean ai) {
        try {
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", tenderService.parsePdf(file, ai));
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to parse PDF: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping(value = "/{id}/upload-source-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadSourcePdf(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            TenderWrapper data = tenderService.uploadSourcePdf(id, file);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "PDF stored successfully");
            res.put("data", data);
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to store PDF: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}/download-source-pdf")
    public ResponseEntity<byte[]> downloadSourcePdf(
            @PathVariable Long id,
            @RequestParam(value = "forceDownload", defaultValue = "false") boolean forceDownload) {
        try {
            TenderEntity t = tenderService.getSourcePdfEntity(id);
            String mime = t.getSourcePdfMimeType() != null ? t.getSourcePdfMimeType() : "application/pdf";
            String fileName = t.getSourcePdfName() != null ? t.getSourcePdfName() : "tender.pdf";
            boolean inline = !forceDownload && isInlineMimeType(mime);
            String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + fileName + "\"";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .contentType(MediaType.parseMediaType(mime))
                    .contentLength(t.getSourcePdfData().length)
                    .body(t.getSourcePdfData());
        } catch (CustomException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── Excel template: download (blank or pre-filled) / import (stateless) / re-check ──

    /** {@code options} are the app's dropdown vocabularies; {@code tender} pre-fills the template. */
    public record ExcelTemplateRequest(TenderExcelOptions options, TenderWrapper tender) {}

    /**
     * Values as edited in the review, to be re-checked by the import's own rules
     * — and against the import's PDF, which the session holds under {@code importId}.
     */
    public record ExcelValidateRequest(TenderExcelOptions options,
                                       Map<String, String> fields,
                                       Map<String, String> fieldSources,
                                       List<Map<String, String>> eligibilityCriteria,
                                       List<Map<String, String>> documents,
                                       List<Map<String, String>> boqItems,
                                       String version,
                                       String importId) {}

    @PostMapping("/excel/template")
    public ResponseEntity<byte[]> excelTemplate(@RequestBody(required = false) ExcelTemplateRequest request) {
        try {
            TenderWrapper prefill = request == null ? null : request.tender();
            byte[] bytes = tenderExcelService.template(prefill, request == null ? null : request.options());
            String ref = prefill != null && prefill.getTenderNumber() != null && !prefill.getTenderNumber().isBlank()
                    ? "-" + prefill.getTenderNumber().replaceAll("[^A-Za-z0-9._-]+", "_") : "";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Tender-Template" + ref + ".xlsx\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Reads the filled template into a review, checking every value against the
     * tender PDF: the one uploaded with it, or — for an existing tender, when
     * none is uploaded — the one stored on the tender. Nothing is saved.
     */
    @PostMapping(value = "/excel/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> excelImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pdf", required = false) MultipartFile pdf,
            @RequestParam(value = "tenderId", required = false) Long tenderId,
            @RequestParam(value = "options", required = false) String optionsJson,
            HttpSession session) {
        try {
            TenderExcelOptions options = optionsJson == null || optionsJson.isBlank()
                    ? TenderExcelOptions.empty()
                    : objectMapper.readValue(optionsJson, TenderExcelOptions.class);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", tenderExcelService.importFile(file, pdf, tenderId, options, session));
            return ResponseEntity.ok(res);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to read the Excel file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/excel/validate")
    public ResponseEntity<Map<String, Object>> excelValidate(@RequestBody ExcelValidateRequest request,
                                                             HttpSession session) {
        try {
            Map<String, List<Map<String, String>>> tables = new HashMap<>();
            tables.put("eligibilityCriteria", request.eligibilityCriteria());
            tables.put("documents", request.documents());
            tables.put("boqItems", request.boqItems());
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", tenderExcelService.validate(request.fields(), request.fieldSources(), tables,
                    request.options(), request.version(), request.importId(), session));
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return error("Failed to check the values: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean isInlineMimeType(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase();
        return m.equals("application/pdf") || m.startsWith("image/");
    }

    private ResponseEntity<Map<String, Object>> error(String message, HttpStatus status) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", false);
        res.put("message", message);
        return ResponseEntity.status(status).body(res);
    }
}
