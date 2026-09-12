package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.InfrastructureMasterListVersionEntity;
import com.istlgroup.istl_group_crm_backend.repo.AppConfigRepository;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureMasterListVersionRepository;
import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListSyncService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InfrastructureMasterListStatusWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin/status visibility for the Infrastructure Master List sync — current
 * version, last check/sync outcome, and a manual trigger for testing without
 * waiting for the last-day-of-month schedule. Follows the same
 * {@code /admin/...} convention as {@code DropdownAdminController}.
 */
@RestController
@RequestMapping("/admin/infrastructure-master-list")
@RequiredArgsConstructor
public class InfrastructureMasterListAdminController {

    private final InfrastructureMasterListVersionRepository versionRepository;
    private final AppConfigRepository appConfigRepository;
    private final InfrastructureMasterListSyncService syncService;

    @GetMapping("/status")
    public ResponseEntity<InfrastructureMasterListStatusWrapper> status() {
        InfrastructureMasterListVersionEntity current = versionRepository.findByIsCurrentTrue().orElse(null);

        InfrastructureMasterListStatusWrapper wrapper = new InfrastructureMasterListStatusWrapper();
        if (current != null) {
            wrapper.setCurrentVersionLabel(current.getVersionLabel());
            wrapper.setNotificationNumber(current.getNotificationNumber());
            wrapper.setNotificationDate(current.getNotificationDate());
            wrapper.setSourcePageUrl(current.getSourcePageUrl());
            wrapper.setPdfUrl(current.getPdfUrl());
            wrapper.setDocumentHash(current.getDocumentHash());
            wrapper.setFetchedAt(current.getFetchedAt() == null ? null : current.getFetchedAt().toString());
        }
        wrapper.setLastCheckAt(configValue("INFRA_HML_LAST_CHECK_AT"));
        wrapper.setLastSyncStatus(configValue("INFRA_HML_LAST_SYNC_STATUS"));
        wrapper.setLastError(configValue("INFRA_HML_LAST_ERROR"));
        return ResponseEntity.ok(wrapper);
    }

    @PostMapping("/sync-now")
    public ResponseEntity<String> syncNow() {
        InfrastructureMasterListSyncService.SyncStatus result = syncService.syncNow();
        return ResponseEntity.ok(result.name());
    }

    private String configValue(String key) {
        return appConfigRepository.findById(key).map(c -> c.getConfigValue()).orElse(null);
    }
}
