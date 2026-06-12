package com.istlgroup.istl_group_crm_backend.scheduler;

import com.istlgroup.istl_group_crm_backend.service.ProjectStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component                       // ✅ Registers as Spring bean
@RequiredArgsConstructor          // ✅ Creates constructor for final fields
@Slf4j                            // ✅ Creates `log` object
@ConditionalOnProperty(
        value = "scheduler.project-stats.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProjectStatsScheduler {

    private final ProjectStatsService projectStatsService;

    /**
     * Recalculate all project statistics every 6 hours
     */
    @Scheduled(cron = "${scheduler.project-stats.full-recalc.cron:0 0 */6 * * *}")
    @ConditionalOnProperty(
            value = "scheduler.project-stats.full-recalc.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public void recalculateAllProjectStats() {
        log.info("Starting scheduled full project statistics recalculation");
        long startTime = System.currentTimeMillis();

        try {
            projectStatsService.recalculateAllProjectStats();
            long duration = System.currentTimeMillis() - startTime;
            log.info("Scheduled full project statistics recalculation completed in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during scheduled full project statistics recalculation", e);
        }
    }

    /**
     * Fix inconsistent project statistics every day at 3 AM
     */
    @Scheduled(cron = "${scheduler.project-stats.fix-inconsistent.cron:0 0 3 * * *}")
    @ConditionalOnProperty(
            value = "scheduler.project-stats.fix-inconsistent.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public void fixInconsistentStats() {
        log.info("Starting scheduled inconsistent stats fix");

        try {
            projectStatsService.fixInconsistentStats();
            log.info("Scheduled inconsistent stats fix completed successfully");
        } catch (Exception e) {
            log.error("Error during scheduled inconsistent stats fix", e);
        }
    }

    /**
     * Health check
     */
    @Scheduled(cron = "0 0 * * * *")
    @ConditionalOnProperty(
            value = "scheduler.project-stats.health-check.enabled",
            havingValue = "true",
            matchIfMissing = false
    )
    public void healthCheck() {
        log.info("ProjectStatsScheduler is active and healthy");
    }
}
