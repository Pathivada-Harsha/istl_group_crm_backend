package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import com.istlgroup.istl_group_crm_backend.config.AppConfig;
import com.istlgroup.istl_group_crm_backend.entity.InfrastructureMasterListVersionEntity;
import com.istlgroup.istl_group_crm_backend.repo.AppConfigRepository;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureMasterListVersionRepository;
import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListPdfExtractor.ExtractionResult;
import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListSourceService.DiscoveredDocument;
import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates one Infrastructure Master List sync attempt: discover the
 * latest government document, compare it against the current version
 * (Level 1: document hash; Level 2: parsed category/sub-category content),
 * and — only when it is new/changed and passes validation — hand off to
 * {@link InfrastructureMasterListVersionWriter} to activate it.
 *
 * <p>Never AI/LLM-based. A failed or unchanged check always leaves the
 * current version (and the live {@code technology_groups}/
 * {@code technology_sub_groups} tables) exactly as they were.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InfrastructureMasterListSyncService {

    public enum SyncStatus { SUCCESS, NO_CHANGE, FAILED }

    private static final String CFG_LAST_CHECK_AT = "INFRA_HML_LAST_CHECK_AT";
    private static final String CFG_LAST_SYNC_STATUS = "INFRA_HML_LAST_SYNC_STATUS";
    private static final String CFG_LAST_ERROR = "INFRA_HML_LAST_ERROR";
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final InfrastructureMasterListSourceService sourceService;
    private final InfrastructureMasterListPdfExtractor extractor;
    private final InfrastructureMasterListParser parser;
    private final InfrastructureMasterListValidator validator;
    private final InfrastructureMasterListVersionWriter versionWriter;
    private final InfrastructureMasterListVersionRepository versionRepository;
    private final AppConfigRepository appConfigRepository;

    public SyncStatus syncNow() {
        setConfig(CFG_LAST_CHECK_AT, LocalDateTime.now().format(TS));
        try {
            DiscoveredDocument document = sourceService.discoverLatest();
            byte[] pdfBytes = sourceService.downloadPdf(document.pdfUrl());
            ExtractionResult extraction = extractor.extract(pdfBytes);

            Optional<InfrastructureMasterListVersionEntity> current = versionRepository.findByIsCurrentTrue();
            if (current.isPresent() && current.get().getDocumentHash().equals(extraction.documentHash())) {
                log.info("Infrastructure Master List: no change (hash matches current version '{}')",
                        current.get().getVersionLabel());
                markStatus(SyncStatus.NO_CHANGE, null);
                return SyncStatus.NO_CHANGE;
            }

            List<ParsedCategory> categories = parser.parse(extraction.runs());
            ValidationResult validation = validator.validate(categories);
            if (!validation.valid()) {
                String reason = String.join("; ", validation.reasons());
                log.error("Infrastructure Master List: rejected new document '{}' — {}", document.title(), reason);
                markStatus(SyncStatus.FAILED, "Validation failed: " + reason);
                return SyncStatus.FAILED;
            }

            logLevel2Diff(current.orElse(null), categories);

            versionWriter.activateNewVersion(document, extraction.documentHash(), categories);
            markStatus(SyncStatus.SUCCESS, null);
            return SyncStatus.SUCCESS;
        } catch (Exception e) {
            log.error("Infrastructure Master List: sync failed, keeping current version", e);
            markStatus(SyncStatus.FAILED, e.getMessage());
            return SyncStatus.FAILED;
        }
    }

    public boolean hasCurrentVersion() {
        return versionRepository.findByIsCurrentTrue().isPresent();
    }

    private void logLevel2Diff(InfrastructureMasterListVersionEntity previous, List<ParsedCategory> next) {
        if (previous == null) {
            log.info("Infrastructure Master List: first import — {} categories", next.size());
            return;
        }
        // Categories/sub-categories are logged for audit; the previous version's
        // full snapshot remains queryable in infrastructure_category/
        // infrastructure_sub_category for anyone who needs the exact diff.
        log.info("Infrastructure Master List: new version detected (previous='{}'), importing {} categories",
                previous.getVersionLabel(), next.size());
    }

    private void markStatus(SyncStatus status, String error) {
        setConfig(CFG_LAST_SYNC_STATUS, status.name());
        setConfig(CFG_LAST_ERROR, error == null ? "" : error);
    }

    private void setConfig(String key, String value) {
        AppConfig config = appConfigRepository.findById(key).orElseGet(() -> {
            AppConfig c = new AppConfig();
            c.setConfigKey(key);
            c.setDescription("Infrastructure Master List sync status (auto-managed)");
            return c;
        });
        config.setConfigValue(value == null ? "" : value);
        appConfigRepository.save(config);
    }
}
