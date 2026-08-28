package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.entity.VendorKycEntity;
import com.istlgroup.istl_group_crm_backend.service.VendorKycService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * KYC document endpoints for vendors.
 * Files are stored as LONGBLOB in vendor_kyc_documents table.
 *
 * GET    /vendors/{vendorId}/kyc
 *        → metadata map: docType → { docNumber, fileName, fileType, fileSize, hasFile, fileUrl, uploadedAt }
 *
 * POST   /vendors/{vendorId}/kyc/upload
 *        → multipart: file (required), docType (required), docNumber (optional)
 *        → stores bytes in DB, returns { success, docType, fileName, fileUrl }
 *
 * GET    /vendors/{vendorId}/kyc/{docType}/file
 *        → streams raw file bytes with correct Content-Type header (for view/download)
 *
 * PATCH  /vendors/{vendorId}/kyc/{docType}/number
 *        → body: { "docNumber": "22AAAAA0000A1Z5" }
 *        → updates doc number without touching the file
 *
 * DELETE /vendors/{vendorId}/kyc/{docType}
 *        → removes the row (and binary) from DB
 */
@RestController
@RequestMapping("/vendors/{vendorId}/kyc")
@RequiredArgsConstructor
@Slf4j
public class VendorKycController {

    private final VendorKycService kycService;

    // ── GET metadata for all docs ─────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> getKycDocs(@PathVariable Long vendorId) {
        try {
            Map<String, Map<String, Object>> docs = kycService.getKycDocs(vendorId);
            return ResponseEntity.ok(docs);
        } catch (RuntimeException e) {
            log.error("GET KYC failed — vendor {}: {}", vendorId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error GET KYC — vendor {}", vendorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    // ── UPLOAD a document (store bytes in DB) ─────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadKycDoc(
            @PathVariable Long vendorId,
            @RequestParam("docType")                            String docType,
            @RequestParam(value = "docNumber", required = false) String docNumber,
            @RequestParam("file")                               MultipartFile file,
            @ActingUserId Long userId
    ) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
            }
            if (docType == null || docType.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "docType is required"));
            }

            Map<String, Object> result = kycService.uploadKycDoc(vendorId, docType, docNumber, file, userId);
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            log.error("Upload KYC failed — vendor {}, docType {}: {}", vendorId, docType, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error uploading KYC — vendor {}, docType {}", vendorId, docType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed"));
        }
    }

    // ── DOWNLOAD / VIEW a file — streams raw bytes ────────────────────────────
    @GetMapping("/{docType}/file")
    public ResponseEntity<?> downloadKycFile(
            @PathVariable Long   vendorId,
            @PathVariable String docType
    ) {
        try {
            VendorKycEntity doc = kycService.getKycDocWithFile(vendorId, docType);

            if (doc.getFileData() == null || doc.getFileData().length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No file uploaded for this document"));
            }

            String contentType = doc.getFileType() != null
                ? doc.getFileType()
                : "application/octet-stream";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            // inline = opens in browser; attachment = forces download
            headers.setContentDispositionFormData("inline", doc.getFileName());
            headers.setContentLength(doc.getFileData().length);

            return new ResponseEntity<>(doc.getFileData(), headers, HttpStatus.OK);

        } catch (RuntimeException e) {
            log.error("Download KYC failed — vendor {}, docType {}: {}", vendorId, docType, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error downloading KYC — vendor {}, docType {}", vendorId, docType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Download failed"));
        }
    }

    // ── UPDATE doc number only ────────────────────────────────────────────────
    @PatchMapping("/{docType}/number")
    public ResponseEntity<?> updateDocNumber(
            @PathVariable Long   vendorId,
            @PathVariable String docType,
            @RequestBody  Map<String, String> body
    ) {
        try {
            kycService.updateDocNumber(vendorId, docType, body.getOrDefault("docNumber", ""));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Update doc number failed — vendor {}, docType {}", vendorId, docType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── DELETE one doc ────────────────────────────────────────────────────────
    @DeleteMapping("/{docType}")
    public ResponseEntity<?> deleteKycDoc(
            @PathVariable Long   vendorId,
            @PathVariable String docType
    ) {
        try {
            kycService.deleteKycDoc(vendorId, docType);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Delete KYC failed — vendor {}, docType {}", vendorId, docType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage())); 
        }
    }
}