package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.service.BorrowerService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerWrapper;

/**
 * Borrower Registry REST API. Follows the TenderController conventions: a
 * {@code {success, message, data}} envelope and the {@code User-Id} header for
 * the current user. Protected by SessionFilter like every non-login endpoint,
 * so no security config change is needed.
 */
@RestController
@RequestMapping("/borrower")
public class BorrowerController {

    private static final Logger log = LoggerFactory.getLogger(BorrowerController.class);

    @Autowired
    private BorrowerService borrowerService;

    // ── borrowers ───────────────────────────────────────────────────────────

    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAll(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "category", required = false) String category) {
        try {
            List<BorrowerWrapper> data = borrowerService.getAll(search, category);
            return ok(data, null);
        } catch (Exception e) {
            log.error("Failed to load borrowers", e);
            return error("Failed to load borrowers: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Categories present across live sanctions. Served from the database rather
     * than derived on the client, so the dropdown lists every category on file
     * instead of only those on the rows currently displayed.
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> categories() {
        try {
            return ok(borrowerService.getCategories(), null);
        } catch (Exception e) {
            log.error("Failed to load categories", e);
            return error("Failed to load categories", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        try {
            return ok(borrowerService.getById(id), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return error("Failed to load borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody BorrowerWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.createBorrower(body, userId), "Borrower created");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to create borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody BorrowerWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.updateBorrower(id, body, userId), "Borrower updated");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to update borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            borrowerService.deleteBorrower(id, userId);
            return ok(null, "Borrower deleted");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to delete borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Find-or-create by name, used by the review screen before saving.
     *
     * <p>Takes a whole {@link BorrowerWrapper} rather than just the name so the
     * borrower-level values the letter carried — promoter, guarantor, group,
     * Cat / Sub Cat, SL ref. — aren't dropped on the floor. The older
     * {@code {"borrowerName": "..."}} payload still deserialises unchanged.
     */
    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestBody BorrowerWrapper body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.resolveBorrower(body, userId), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to resolve borrower: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── import ──────────────────────────────────────────────────────────────

    /**
     * Stateless parse. Reads the letter and returns a field map for the review
     * screen; writes nothing, so it works before a borrower exists.
     */
    @PostMapping(value = "/parse-sanction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> parseSanction(
            @RequestParam("file") MultipartFile file) {
        try {
            return ok(borrowerService.parseSanction(file), null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to read the document: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── sanctions ───────────────────────────────────────────────────────────

    @PostMapping("/sanction/save")
    public ResponseEntity<Map<String, Object>> saveSanction(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            BorrowerSanctionWrapper sanction =
                    mapper.convertValue(body.get("sanction"), BorrowerSanctionWrapper.class);

            String rawJson = body.get("rawExtracted") == null
                    ? null : mapper.writeValueAsString(body.get("rawExtracted"));

            return ok(borrowerService.saveSanction(sanction, userId, rawJson), "Sanction saved");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to save sanction: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/sanction/delete/{id}")
    public ResponseEntity<Map<String, Object>> deleteSanction(
            @PathVariable Long id,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            borrowerService.deleteSanction(id, userId);
            return ok(null, "Sanction deleted");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to delete sanction: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── the letter ──────────────────────────────────────────────────────────

    @PostMapping(value = "/sanction/{id}/upload-doc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadDoc(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        try {
            return ok(borrowerService.uploadDocument(id, file, userId), "Document stored");
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error("Failed to store the document: " + e.getMessage(),
                         HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * In-page preview. PDFs are served by the download endpoint and rendered in
     * an iframe; Word files can't be, so they come back here as sanitised HTML
     * the viewer drops into the page. Either way the user never has to download
     * a file just to read it.
     */
    @GetMapping("/sanction/{id}/preview-doc")
    public ResponseEntity<Map<String, Object>> previewDoc(@PathVariable Long id) {
        try {
            Map<String, Object> payload = borrowerService.buildPreview(id);
            return ok(payload, null);
        } catch (CustomException e) {
            return error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Throwable t) {
            // Catch Throwable, not Exception: POI raises Errors of its own
            // (NoClassDefFoundError when a schema jar is missing, OOM on a
            // huge file) and those would otherwise reach the global handler,
            // which replaces the cause with "Something went wrong" and logs
            // nothing — leaving no way to tell what actually failed.
            log.error("Preview failed for sanction {}", id, t);
            String cause = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            return error("Could not render this document (" + cause
                       + "). You can still download it.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Serve the stored letter. Defaults to inline so a PDF opens in the viewer;
     * pass forceDownload=true for the download action.
     */
    @GetMapping("/sanction/{id}/download-doc")
    public ResponseEntity<byte[]> downloadDoc(
            @PathVariable Long id,
            @RequestParam(value = "forceDownload", defaultValue = "false") boolean forceDownload) {
        try {
            BorrowerSanctionEntity s = borrowerService.getDocumentEntity(id);
            String mime = s.getSanctionDocMime() != null
                    ? s.getSanctionDocMime() : "application/octet-stream";
            String fileName = s.getSanctionDocName() != null
                    ? s.getSanctionDocName() : "sanction-letter";
            boolean inline = !forceDownload && mime.toLowerCase().contains("pdf");
            String disposition = (inline ? "inline" : "attachment")
                    + "; filename=\"" + fileName + "\"";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .contentType(MediaType.parseMediaType(mime))
                    .contentLength(s.getSanctionDocData().length)
                    .body(s.getSanctionDocData());
        } catch (CustomException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── envelope helpers ────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        if (message != null) res.put("message", message);
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    private ResponseEntity<Map<String, Object>> error(String message, HttpStatus status) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", false);
        res.put("message", message);
        return ResponseEntity.status(status).body(res);
    }
}