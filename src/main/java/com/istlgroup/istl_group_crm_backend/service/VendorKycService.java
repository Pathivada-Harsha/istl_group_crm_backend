package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import com.istlgroup.istl_group_crm_backend.entity.VendorKycEntity;
import com.istlgroup.istl_group_crm_backend.repo.VendorKycRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Handles KYC document storage for vendors.
 * Files are stored as LONGBLOB in the vendor_kyc_documents table
 * (same pattern as InvoiceAttachmentEntity / OrderBookEntity).
 *
 * Download endpoint: GET /vendors/{vendorId}/kyc/{docType}/file
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorKycService {

    private final VendorKycRepository kycRepo;
    private final VendorRepository    vendorRepo;

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB

    /** Mandatory doc types — all must have number + file for kycVerified = true */
    private static final Set<String> MANDATORY_DOCS = Set.of(
        "gst_certificate",
        "pan_card",
        "incorporation_certificate",
        "cancelled_cheque"
    );

    // ── GET: all KYC docs for a vendor (metadata only, no binary) ────────────
    /**
     * Returns a map of docType → { docNumber, fileName, fileType, fileSize, uploadedAt, hasFile }
     * The frontend uses this to know which docs are uploaded without downloading the binary.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> getKycDocs(Long vendorId) {
        vendorRepo.findById(vendorId)
            .orElseThrow(() -> new RuntimeException("Vendor not found: " + vendorId));

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (VendorKycEntity doc : kycRepo.findByVendorIdWithoutFileData(vendorId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("docNumber",  doc.getDocNumber());
            entry.put("fileName",   doc.getFileName());
            entry.put("fileType",   doc.getFileType());
            entry.put("fileSize",   doc.getFileSize());
            entry.put("hasFile",    doc.getFileName() != null && !doc.getFileName().isBlank());
            // fileUrl points to the download endpoint so frontend can call it to view/download
            entry.put("fileUrl",    "/vendors/" + vendorId + "/kyc/" + doc.getDocType() + "/file");
            entry.put("uploadedAt", doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : null);
            result.put(doc.getDocType(), entry);
        }
        return result;
    }

    // ── DOWNLOAD: return raw bytes for a single doc ───────────────────────────
    @Transactional(readOnly = true)
    public VendorKycEntity getKycDocWithFile(Long vendorId, String docType) {
        return kycRepo.findByVendorIdAndDocType(vendorId, docType)
            .orElseThrow(() -> new RuntimeException("KYC document not found"));
    }

    // ── UPLOAD: save or replace a KYC document ───────────────────────────────
    @Transactional
    public Map<String, Object> uploadKycDoc(
            Long vendorId,
            String docType,
            String docNumber,
            MultipartFile file,
            Long uploadedByUserId
    ) throws IOException {

        VendorEntity vendor = vendorRepo.findById(vendorId)
            .orElseThrow(() -> new RuntimeException("Vendor not found: " + vendorId));

        if (file == null || file.isEmpty()) {
            throw new RuntimeException("File is required");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("File exceeds 10 MB limit");
        }

        // Upsert: find existing row or create new one
        VendorKycEntity kyc = kycRepo
            .findByVendorIdAndDocType(vendorId, docType)
            .orElseGet(() -> VendorKycEntity.builder()
                .vendorId(vendorId)
                .docType(docType)
                .build()
            );

        kyc.setDocNumber(docNumber != null ? docNumber.trim() : kyc.getDocNumber());
        kyc.setFileName(file.getOriginalFilename());
        kyc.setFileType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        kyc.setFileData(file.getBytes());   // store raw bytes in LONGBLOB
        kyc.setFileSize(file.getSize());
        kyc.setUploadedAt(LocalDateTime.now());
        kyc.setUploadedBy(uploadedByUserId);
        kycRepo.save(kyc);

        log.info("KYC doc uploaded: vendor={}, docType={}, file={}, size={}",
                 vendorId, docType, file.getOriginalFilename(), file.getSize());

        // Refresh kycVerified flag on vendor
        refreshKycVerifiedFlag(vendor);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",  true);
        resp.put("docType",  docType);
        resp.put("fileName", file.getOriginalFilename());
        resp.put("fileUrl",  "/vendors/" + vendorId + "/kyc/" + docType + "/file");
        return resp;
    }

    // ── UPDATE doc number only (no file) ─────────────────────────────────────
    @Transactional
    public void updateDocNumber(Long vendorId, String docType, String docNumber) {
        VendorKycEntity kyc = kycRepo
            .findByVendorIdAndDocType(vendorId, docType)
            .orElseGet(() -> VendorKycEntity.builder()
                .vendorId(vendorId)
                .docType(docType)
                .build()
            );
        kyc.setDocNumber(docNumber != null ? docNumber.trim() : null);
        kycRepo.save(kyc);
    }

    // ── DELETE one doc ────────────────────────────────────────────────────────
    @Transactional
    public void deleteKycDoc(Long vendorId, String docType) {
        kycRepo.findByVendorIdAndDocType(vendorId, docType)
            .ifPresent(kycRepo::delete);
        vendorRepo.findById(vendorId).ifPresent(this::refreshKycVerifiedFlag);
    }

    // ── PRIVATE helpers ───────────────────────────────────────────────────────

    /**
     * Sets vendor.kycVerified = true when every mandatory doc type has
     * both a non-blank docNumber AND file bytes stored.
     */
    private void refreshKycVerifiedFlag(VendorEntity vendor) {
        List<VendorKycEntity> docs = kycRepo.findByVendorIdWithoutFileData(vendor.getId());
        Map<String, VendorKycEntity> byType = new HashMap<>();
        docs.forEach(d -> byType.put(d.getDocType(), d));

        boolean verified = MANDATORY_DOCS.stream().allMatch(type -> {
            VendorKycEntity doc = byType.get(type);
            if (doc == null) return false;
            boolean hasNumber = doc.getDocNumber() != null && !doc.getDocNumber().isBlank();
            boolean hasFile   = doc.getFileName()  != null && !doc.getFileName().isBlank();
            return hasNumber && hasFile;
        });

        vendor.setKycVerified(verified);
        vendorRepo.save(vendor);
        log.info("Vendor {} kycVerified set to {}", vendor.getId(), verified);
    }
}