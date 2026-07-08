package com.istlgroup.istl_group_crm_backend.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.entity.ActivityDailyStatsEntity;
import com.istlgroup.istl_group_crm_backend.repo.ActivityDailyStatsRepo;
import com.istlgroup.istl_group_crm_backend.repo.AuditLogRepo;
import com.istlgroup.istl_group_crm_backend.repo.LoginHistoryRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserSessionRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ActivityStatsRollupJob — fills activity_daily_stats so long-range dashboard
 * queries never scan the raw log tables. Runs nightly for yesterday, plus a
 * light hourly refresh of today's row (also captures peak active sessions).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "scheduler.activity-stats.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ActivityStatsRollupJob {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ActivityDailyStatsRepo statsRepo;
    private final LoginHistoryRepo loginHistoryRepo;
    private final AuditLogRepo auditLogRepo;
    private final UserSessionRepo sessionRepo;

    /** 00:15 every night — final numbers for yesterday. */
    @Scheduled(cron = "${scheduler.activity-stats.nightly.cron:0 15 0 * * *}")
    public void rollupYesterday() {
        rollup(LocalDate.now(IST).minusDays(1));
    }

    /** Hourly — keeps today's row fresh and tracks peak active sessions. */
    @Scheduled(cron = "${scheduler.activity-stats.hourly.cron:0 5 * * * *}")
    public void rollupToday() {
        rollup(LocalDate.now(IST));
    }

    private void rollup(LocalDate day) {
        try {
            LocalDateTime from = day.atStartOfDay();
            LocalDateTime to   = from.plusDays(1);

            ActivityDailyStatsEntity s = statsRepo.findById(day)
                    .orElseGet(() -> {
                        ActivityDailyStatsEntity n = new ActivityDailyStatsEntity();
                        n.setStatDate(day);
                        return n;
                    });

            s.setTotalLogins(loginHistoryRepo.countSuccessBetween(from, to));
            s.setFailedLogins(loginHistoryRepo.countFailedBetween(from, to));
            s.setUniqueUsers(loginHistoryRepo.countUniqueUsersBetween(from, to));
            s.setUniqueDevices(loginHistoryRepo.countUniqueDevicesBetween(from, to));
            s.setUniqueLocations(loginHistoryRepo.countUniqueCitiesBetween(from, to));
            s.setTotalActivities(auditLogRepo.countBetween(from, to));

            long nowActive = sessionRepo.countActive();
            if (s.getPeakActiveSessions() == null || nowActive > s.getPeakActiveSessions()) {
                s.setPeakActiveSessions(nowActive);
            }
            s.setUpdatedAt(LocalDateTime.now(IST));
            statsRepo.save(s);
        } catch (Exception e) {
            log.error("Daily stats rollup failed for {}", day, e);
        }
    }
}
