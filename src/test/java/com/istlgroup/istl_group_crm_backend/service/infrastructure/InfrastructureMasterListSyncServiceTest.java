package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import com.istlgroup.istl_group_crm_backend.config.AppConfig;
import com.istlgroup.istl_group_crm_backend.entity.InfrastructureMasterListVersionEntity;
import com.istlgroup.istl_group_crm_backend.repo.AppConfigRepository;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureMasterListVersionRepository;
import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListSourceService.DiscoveredDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mocks discovery/extraction/parsing/writing so these tests pin down the
 * orchestration logic in isolation: a same-hash document is a no-op, a
 * changed-but-invalid document never touches the writer, and a changed
 * valid document is handed to {@link InfrastructureMasterListVersionWriter}
 * exactly once. Parsing itself is covered end-to-end against the real PDFs
 * by {@link InfrastructureMasterListParserTest}.
 */
@ExtendWith(MockitoExtension.class)
class InfrastructureMasterListSyncServiceTest {

    @Mock private InfrastructureMasterListSourceService sourceService;
    @Mock private InfrastructureMasterListPdfExtractor extractor;
    @Mock private InfrastructureMasterListParser parser;
    @Mock private InfrastructureMasterListValidator validator;
    @Mock private InfrastructureMasterListVersionWriter versionWriter;
    @Mock private InfrastructureMasterListVersionRepository versionRepository;
    @Mock private AppConfigRepository appConfigRepository;

    private InfrastructureMasterListSyncService syncService;

    private static final byte[] PDF_BYTES = {1, 2, 3};
    private static final DiscoveredDocument DOCUMENT = new DiscoveredDocument(
            "Updated Harmonized Master List of Infrastructure sub-sectors 2025",
            "F.No. 13/1/2025-IPP", LocalDate.of(2025, 9, 19),
            "https://www.pppinindia.gov.in/circulars_and_orders",
            "https://www.pppinindia.gov.in/report/Latest%20HML.pdf");

    @BeforeEach
    void setUp() {
        syncService = new InfrastructureMasterListSyncService(
                sourceService, extractor, parser, validator, versionWriter, versionRepository, appConfigRepository);
        when(appConfigRepository.findById(anyString())).thenReturn(Optional.empty());
        when(appConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sourceService.discoverLatest()).thenReturn(DOCUMENT);
        when(sourceService.downloadPdf(DOCUMENT.pdfUrl())).thenReturn(PDF_BYTES);
    }

    private InfrastructureMasterListVersionEntity currentVersion(String hash) {
        InfrastructureMasterListVersionEntity v = new InfrastructureMasterListVersionEntity();
        v.setDocumentHash(hash);
        v.setVersionLabel("previous");
        v.setIsCurrent(true);
        return v;
    }

    @Test
    void identicalHashIsANoOpAndNeverWritesANewVersion() throws Exception {
        when(extractor.extract(PDF_BYTES))
                .thenReturn(new InfrastructureMasterListPdfExtractor.ExtractionResult("same-hash", List.of()));
        when(versionRepository.findByIsCurrentTrue()).thenReturn(Optional.of(currentVersion("same-hash")));

        InfrastructureMasterListSyncService.SyncStatus status = syncService.syncNow();

        assertEquals(InfrastructureMasterListSyncService.SyncStatus.NO_CHANGE, status);
        verify(versionWriter, never()).activateNewVersion(any(), anyString(), any());
        verify(parser, never()).parse(any());
    }

    @Test
    void changedHashButFailedValidationNeverWritesANewVersion() throws Exception {
        List<ParsedCategory> parsed = List.of(new ParsedCategory("Bad", 1, List.of()));
        when(extractor.extract(PDF_BYTES))
                .thenReturn(new InfrastructureMasterListPdfExtractor.ExtractionResult("new-hash", List.of()));
        when(versionRepository.findByIsCurrentTrue()).thenReturn(Optional.of(currentVersion("old-hash")));
        when(parser.parse(any())).thenReturn(parsed);
        when(validator.validate(parsed))
                .thenReturn(new InfrastructureMasterListValidator.ValidationResult(false, List.of("no sub-categories")));

        InfrastructureMasterListSyncService.SyncStatus status = syncService.syncNow();

        assertEquals(InfrastructureMasterListSyncService.SyncStatus.FAILED, status);
        verify(versionWriter, never()).activateNewVersion(any(), anyString(), any());
    }

    @Test
    void changedHashAndValidDataActivatesExactlyOneNewVersion() throws Exception {
        List<ParsedCategory> parsed = List.of(new ParsedCategory("Energy", 1,
                List.of(new ParsedSubCategory("Electricity Generation", 1))));
        when(extractor.extract(PDF_BYTES))
                .thenReturn(new InfrastructureMasterListPdfExtractor.ExtractionResult("new-hash", List.of()));
        when(versionRepository.findByIsCurrentTrue()).thenReturn(Optional.of(currentVersion("old-hash")));
        when(parser.parse(any())).thenReturn(parsed);
        when(validator.validate(parsed)).thenReturn(InfrastructureMasterListValidator.ValidationResult.ok());

        InfrastructureMasterListSyncService.SyncStatus status = syncService.syncNow();

        assertEquals(InfrastructureMasterListSyncService.SyncStatus.SUCCESS, status);
        verify(versionWriter, times(1)).activateNewVersion(DOCUMENT, "new-hash", parsed);
    }

    @Test
    void firstEverImportWithNoCurrentVersionStillActivates() throws Exception {
        List<ParsedCategory> parsed = List.of(new ParsedCategory("Energy", 1,
                List.of(new ParsedSubCategory("Electricity Generation", 1))));
        when(extractor.extract(PDF_BYTES))
                .thenReturn(new InfrastructureMasterListPdfExtractor.ExtractionResult("first-hash", List.of()));
        when(versionRepository.findByIsCurrentTrue()).thenReturn(Optional.empty());
        when(parser.parse(any())).thenReturn(parsed);
        when(validator.validate(parsed)).thenReturn(InfrastructureMasterListValidator.ValidationResult.ok());

        InfrastructureMasterListSyncService.SyncStatus status = syncService.syncNow();

        assertEquals(InfrastructureMasterListSyncService.SyncStatus.SUCCESS, status);
        verify(versionWriter, times(1)).activateNewVersion(DOCUMENT, "first-hash", parsed);
    }

    @Test
    void sourceDiscoveryFailureLeavesCurrentVersionUntouchedAndMarksFailed() {
        when(sourceService.discoverLatest())
                .thenThrow(new InfrastructureMasterListSourceService.InfrastructureMasterListSourceException("site down"));

        InfrastructureMasterListSyncService.SyncStatus status = syncService.syncNow();

        assertEquals(InfrastructureMasterListSyncService.SyncStatus.FAILED, status);
        verify(versionWriter, never()).activateNewVersion(any(), anyString(), any());
        verify(appConfigRepository, times(1)).save(argThatConfigKeyIs("INFRA_HML_LAST_ERROR"));
    }

    private static AppConfig argThatConfigKeyIs(String key) {
        return org.mockito.ArgumentMatchers.argThat(c -> c != null && key.equals(c.getConfigKey()));
    }
}
