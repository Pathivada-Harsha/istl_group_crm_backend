package com.istlgroup.istl_group_crm_backend.migration;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.repo.BillRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * One-time migration runner that reads files stored on disk (uploads folder)
 * into the corresponding BLOB columns in the DB row.
 *
 * Safe to run multiple times — only migrates rows that:
 *   - have a file_path populated (meaning a file was uploaded historically)
 *   - but have file_data still NULL (not yet migrated)
 *
 * After the app confirms all rows are migrated correctly, the old path columns
 * can be left as-is for reference (they are no longer used for serving files).
 *
 * ENABLE/DISABLE: set  file.migration.enabled=true  in application properties.
 * Default is FALSE — you opt in explicitly on the server.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileMigrationRunner implements ApplicationRunner {

    private final BillRepository           billRepository;
    private final PurchaseOrderRepository  purchaseOrderRepository;
    private final ProposalsRepo      proposalsRepository;

    // Inject via application.properties: file.migration.enabled=true
    @org.springframework.beans.factory.annotation.Value("${file.migration.enabled:false}")
    private boolean migrationEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!migrationEnabled) {
            log.info("[FileMigration] Skipped — set file.migration.enabled=true to run.");
            return;
        }
        log.info("[FileMigration] ===== Starting file-to-BLOB migration =====");
        migrateBills();
        migratePurchaseOrders();
        migrateProposals();
        log.info("[FileMigration] ===== Migration complete =====");
    }

    // =========================================================================
    // Bills
    // =========================================================================

    @Transactional
    public void migrateBills() {
        // Only rows that have a path but no blob data yet
        List<BillEntity> rows = billRepository.findAll().stream()
                .filter(b -> b.getBillFilePath() != null
                          && !b.getBillFilePath().isBlank()
                          && b.getBillFileData() == null)
                .toList();

        log.info("[FileMigration] Bills to migrate: {}", rows.size());
        int ok = 0, skip = 0, fail = 0;

        for (BillEntity bill : rows) {
            try {
                Path path = resolveServerPath(bill.getBillFilePath());
                if (!Files.exists(path)) {
                    log.warn("[FileMigration] Bill id={} file not found on disk: {}", bill.getId(), path);
                    skip++;
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length == 0) {
                    log.warn("[FileMigration] Bill id={} file is empty: {}", bill.getId(), path);
                    skip++;
                    continue;
                }

                bill.setBillFileData(bytes);
                bill.setBillFileContentType(detectContentType(path, bill.getBillFileName()));
                billRepository.save(bill);
                ok++;
                log.info("[FileMigration] Bill id={} migrated ({} bytes)", bill.getId(), bytes.length);

            } catch (IOException e) {
                fail++;
                log.error("[FileMigration] Bill id={} failed: {}", bill.getId(), e.getMessage());
            }
        }
        log.info("[FileMigration] Bills — OK:{} SKIPPED:{} FAILED:{}", ok, skip, fail);
    }

    // =========================================================================
    // Purchase Orders
    // =========================================================================

    @Transactional
    public void migratePurchaseOrders() {
        List<PurchaseOrderEntity> rows = purchaseOrderRepository.findAll().stream()
                .filter(po -> po.getPoFilePath() != null
                           && !po.getPoFilePath().isBlank()
                           && po.getPoFileData() == null)
                .toList();

        log.info("[FileMigration] Purchase Orders to migrate: {}", rows.size());
        int ok = 0, skip = 0, fail = 0;

        for (PurchaseOrderEntity po : rows) {
            try {
                Path path = resolveServerPath(po.getPoFilePath());
                if (!Files.exists(path)) {
                    log.warn("[FileMigration] PO id={} file not found on disk: {}", po.getId(), path);
                    skip++;
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length == 0) {
                    log.warn("[FileMigration] PO id={} file is empty: {}", po.getId(), path);
                    skip++;
                    continue;
                }

                po.setPoFileData(bytes);
                po.setPoFileContentType(detectContentType(path, po.getPoFileName()));
                purchaseOrderRepository.save(po);
                ok++;
                log.info("[FileMigration] PO id={} migrated ({} bytes)", po.getId(), bytes.length);

            } catch (IOException e) {
                fail++;
                log.error("[FileMigration] PO id={} failed: {}", po.getId(), e.getMessage());
            }
        }
        log.info("[FileMigration] POs — OK:{} SKIPPED:{} FAILED:{}", ok, skip, fail);
    }

    // =========================================================================
    // Proposals
    // =========================================================================

    @Transactional
    public void migrateProposals() {
        List<ProposalsEntity> rows = proposalsRepository.findAll().stream()
                .filter(p -> p.getOfflinePdfPath() != null
                          && !p.getOfflinePdfPath().isBlank()
                          && p.getOfflinePdfData() == null)
                .toList();

        log.info("[FileMigration] Proposals to migrate: {}", rows.size());
        int ok = 0, skip = 0, fail = 0;

        for (ProposalsEntity proposal : rows) {
            try {
                Path path = resolveProposalPath(proposal.getOfflinePdfPath());
                if (!Files.exists(path)) {
                    log.warn("[FileMigration] Proposal id={} file not found on disk: {}", proposal.getId(), path);
                    skip++;
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length == 0) {
                    log.warn("[FileMigration] Proposal id={} file is empty: {}", proposal.getId(), path);
                    skip++;
                    continue;
                }

                proposal.setOfflinePdfData(bytes);
                proposalsRepository.save(proposal);
                ok++;
                log.info("[FileMigration] Proposal id={} migrated ({} bytes)", proposal.getId(), bytes.length);

            } catch (IOException e) {
                fail++;
                log.error("[FileMigration] Proposal id={} failed: {}", proposal.getId(), e.getMessage());
            }
        }
        log.info("[FileMigration] Proposals — OK:{} SKIPPED:{} FAILED:{}", ok, skip, fail);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Resolves a stored path (relative or absolute) to a real Path on the server.
     *
     * Handles the two common formats stored in this app:
     *   - Relative:  "uploads/bills/uuid.pdf"
     *   - Absolute:  "/home/user/app/uploads/bills/uuid.pdf"
     *
     * If the path doesn't exist as-is, also tries resolving it relative to
     * the JVM working directory (server root).
     */
    private Path resolveServerPath(String stored) {
        if (stored == null) return Paths.get("");

        // Normalise Windows backslashes if any (dev machine paths leaked to prod)
        String normalised = stored.replace('\\', '/');

        Path direct = Paths.get(normalised);
        if (Files.exists(direct)) return direct;

        // Try relative to CWD (server is started from project root)
        Path cwd = Paths.get(System.getProperty("user.dir")).resolve(normalised);
        if (Files.exists(cwd)) return cwd;

        // Last resort: strip everything before "uploads/" and resolve from CWD
        int idx = normalised.indexOf("uploads/");
        if (idx >= 0) {
            Path fromUploads = Paths.get(System.getProperty("user.dir"))
                                    .resolve(normalised.substring(idx));
            if (Files.exists(fromUploads)) return fromUploads;
        }

        return direct; // caller checks Files.exists, logs warning
    }

    /**
     * Same logic as ProposalsController.resolveProposalFilePath —
     * specifically handles the legacy Windows absolute path problem for proposals.
     */
    private Path resolveProposalPath(String stored) {
        Path direct = resolveServerPath(stored);
        if (Files.exists(direct)) return direct;

        // Extract just filename, resolve under known upload dir
        String normalised = stored.replace('\\', '/');
        String fileName = Paths.get(normalised).getFileName().toString();
        Path fromDir = Paths.get(System.getProperty("user.dir"))
                            .resolve("uploads/proposals/offline/")
                            .resolve(fileName);
        return fromDir;
    }

    /**
     * Detect MIME type from file extension or actual file bytes.
     * Falls back to application/octet-stream if unknown.
     */
    private String detectContentType(Path path, String originalName) {
        try {
            String ct = Files.probeContentType(path);
            if (ct != null) return ct;
        } catch (IOException ignored) {}

        if (originalName == null) return "application/octet-stream";
        String lower = originalName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}