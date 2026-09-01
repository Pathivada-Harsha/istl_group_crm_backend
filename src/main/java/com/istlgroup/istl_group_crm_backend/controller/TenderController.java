package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
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
import com.istlgroup.istl_group_crm_backend.service.TenderService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderWrapper;

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
            @ActingUserId Long userId) {
        try {
            TenderWrapper created = tenderService.create(request, userId);
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
            @ActingUserId Long userId) {
        try {
            TenderWrapper updated = tenderService.update(id, request, userId);
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
