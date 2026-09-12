package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.config.AppConfig;
import com.istlgroup.istl_group_crm_backend.entity.InfrastructureMasterListVersionEntity;
import com.istlgroup.istl_group_crm_backend.repo.AppConfigRepository;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureMasterListVersionRepository;
import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListSyncService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InfrastructureMasterListStatusWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InfrastructureMasterListAdminControllerTest {

    @Mock private InfrastructureMasterListVersionRepository versionRepository;
    @Mock private AppConfigRepository appConfigRepository;
    @Mock private InfrastructureMasterListSyncService syncService;

    @Test
    void statusReportsCurrentVersionAndLastSyncInfo() {
        InfrastructureMasterListVersionEntity current = new InfrastructureMasterListVersionEntity();
        current.setVersionLabel("Updated Harmonized Master List of Infrastructure sub-sectors 2025");
        current.setNotificationNumber("F.No. 13/1/2025-IPP");
        current.setNotificationDate(LocalDate.of(2025, 9, 19));
        current.setSourcePageUrl("https://www.pppinindia.gov.in/circulars_and_orders");
        current.setPdfUrl("https://www.pppinindia.gov.in/report/Latest%20HML.pdf");
        current.setDocumentHash("abc123");
        current.setFetchedAt(LocalDateTime.of(2026, 9, 11, 2, 0));

        when(versionRepository.findByIsCurrentTrue()).thenReturn(Optional.of(current));
        when(appConfigRepository.findById("INFRA_HML_LAST_CHECK_AT"))
                .thenReturn(Optional.of(configOf("2026-09-11T02:00:00")));
        when(appConfigRepository.findById("INFRA_HML_LAST_SYNC_STATUS"))
                .thenReturn(Optional.of(configOf("SUCCESS")));
        when(appConfigRepository.findById("INFRA_HML_LAST_ERROR"))
                .thenReturn(Optional.of(configOf("")));

        InfrastructureMasterListAdminController controller =
                new InfrastructureMasterListAdminController(versionRepository, appConfigRepository, syncService);

        ResponseEntity<InfrastructureMasterListStatusWrapper> response = controller.status();

        InfrastructureMasterListStatusWrapper body = response.getBody();
        assertEquals("F.No. 13/1/2025-IPP", body.getNotificationNumber());
        assertEquals("SUCCESS", body.getLastSyncStatus());
        assertEquals("abc123", body.getDocumentHash());
    }

    @Test
    void statusWithNoCurrentVersionReturnsNullVersionFields() {
        when(versionRepository.findByIsCurrentTrue()).thenReturn(Optional.empty());
        when(appConfigRepository.findById("INFRA_HML_LAST_CHECK_AT")).thenReturn(Optional.empty());
        when(appConfigRepository.findById("INFRA_HML_LAST_SYNC_STATUS")).thenReturn(Optional.empty());
        when(appConfigRepository.findById("INFRA_HML_LAST_ERROR")).thenReturn(Optional.empty());

        InfrastructureMasterListAdminController controller =
                new InfrastructureMasterListAdminController(versionRepository, appConfigRepository, syncService);

        InfrastructureMasterListStatusWrapper body = controller.status().getBody();

        assertEquals(null, body.getCurrentVersionLabel());
    }

    @Test
    void syncNowDelegatesToTheSyncService() {
        when(syncService.syncNow()).thenReturn(InfrastructureMasterListSyncService.SyncStatus.NO_CHANGE);

        InfrastructureMasterListAdminController controller =
                new InfrastructureMasterListAdminController(versionRepository, appConfigRepository, syncService);

        ResponseEntity<String> response = controller.syncNow();

        assertEquals("NO_CHANGE", response.getBody());
        verify(syncService, times(1)).syncNow();
    }

    private static AppConfig configOf(String value) {
        AppConfig c = new AppConfig();
        c.setConfigValue(value);
        return c;
    }
}
