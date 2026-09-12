package com.istlgroup.istl_group_crm_backend.scheduler;

import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Checks the official PPP India source for a new/changed Harmonized Master
 * List once a day at 2am, but only actually syncs on the last calendar day
 * of the month — deliberately NOT a {@code 28-31} cron day-of-month range
 * (which would fire on every one of those days), but a daily trigger with an
 * explicit {@link YearMonth#atEndOfMonth()} check so 28 Feb / 29 Feb (leap
 * years) / 30- and 31-day months are all handled correctly with one rule.
 *
 * <p>This is a check schedule, not an assumption that the government
 * updates the list monthly — most runs will find nothing new and do
 * nothing. A bootstrap run also fires once on application startup if no
 * master list version has ever been imported yet, so the Category/Sub
 * Category dropdown isn't left empty until the next month-end.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "infrastructure.master-list.sync.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class InfrastructureMasterListSyncJob {

    private final InfrastructureMasterListSyncService syncService;

    @Value("${infrastructure.master-list.sync.bootstrap-on-startup:true}")
    private boolean bootstrapOnStartup;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapIfNeverSynced() {
        if (!bootstrapOnStartup) return;
        try {
            if (!syncService.hasCurrentVersion()) {
                log.info("Infrastructure Master List: no current version on startup — running initial sync");
                syncService.syncNow();
            }
        } catch (Exception e) {
            log.error("Infrastructure Master List: bootstrap sync failed", e);
        }
    }

    @Scheduled(cron = "${infrastructure.master-list.sync.cron:0 0 2 * * *}")
    public void checkOnLastDayOfMonth() {
        LocalDate today = LocalDate.now();
        if (!today.equals(YearMonth.from(today).atEndOfMonth())) {
            return;
        }
        try {
            log.info("Infrastructure Master List: last-day-of-month check starting");
            syncService.syncNow();
        } catch (Exception e) {
            log.error("Infrastructure Master List: scheduled sync failed, will retry next scheduled run", e);
        }
    }
}
