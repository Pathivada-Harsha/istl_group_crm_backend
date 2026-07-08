package com.istlgroup.istl_group_crm_backend.scheduler;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.repo.AuditLogArchiveRepo;
import com.istlgroup.istl_group_crm_backend.repo.AuditLogRepo;
import com.istlgroup.istl_group_crm_backend.repo.LoginHistoryArchiveRepo;
import com.istlgroup.istl_group_crm_backend.repo.LoginHistoryRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LogRetentionJob — automatic archive + cleanup (Feature 7, approved values).
 *
 *   Failed login attempts : DELETED after 1 month           (daily, 02:30)
 *   Login history SUCCESS : hot 12 months → archive         (monthly, 02:00)
 *   Audit logs            : hot 6 months  → archive         (monthly, 02:00)
 *   Page-view (VIEW) rows : DELETED after 3 months          (monthly, 02:00)
 *   Both archives         : DELETED after 24 months total   (monthly, 02:00)
 *
 * All windows are configurable in application properties without code changes.
 * Everything runs in small LIMIT batches so the tables are never locked for
 * more than a moment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "scheduler.log-retention.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LogRetentionJob {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int BATCH = 5000;
    private static final int MAX_BATCHES_PER_RUN = 200; // 1M rows/run ceiling

    private final LoginHistoryRepo loginHistoryRepo;
    private final LoginHistoryArchiveRepo loginHistoryArchiveRepo;
    private final AuditLogRepo auditLogRepo;
    private final AuditLogArchiveRepo auditLogArchiveRepo;

    @Value("${login-activity.retention.failed-login-months:1}")
    private int failedLoginMonths;

    @Value("${login-activity.retention.login-history-hot-months:12}")
    private int loginHistoryHotMonths;

    @Value("${login-activity.retention.audit-hot-months:6}")
    private int auditHotMonths;

    @Value("${login-activity.retention.page-view-months:3}")
    private int pageViewMonths;

    @Value("${login-activity.retention.total-months:24}")
    private int totalMonths;

    /** Daily 02:30 — failed login attempts older than 1 month are deleted. */
    @Scheduled(cron = "${scheduler.log-retention.daily.cron:0 30 2 * * *}")
    public void dailyFailedLoginPurge() {
        LocalDateTime cutoff = LocalDateTime.now(IST).minusMonths(failedLoginMonths);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = loginHistoryRepo.deleteFailedOlderThan(cutoff, BATCH);
            total += deleted;
            if (deleted < BATCH) break;
        }
        if (total > 0) log.info("Retention: deleted {} failed login rows older than {} month(s)",
                total, failedLoginMonths);
    }

    /** Monthly, 1st day 02:00 — archive + purge everything else. */
    @Scheduled(cron = "${scheduler.log-retention.monthly.cron:0 0 2 1 * *}")
    public void monthlyArchiveAndPurge() {
        LocalDateTime now = LocalDateTime.now(IST);
        log.info("Retention: monthly archive/purge starting");

        // 1. Page-view events older than 3 months — deleted directly
        batched("page-view purge",
                () -> auditLogRepo.deletePageViewsOlderThan(now.minusMonths(pageViewMonths), BATCH));

        // 2. Audit logs older than 6 months — move to archive
        LocalDateTime auditCutoff = now.minusMonths(auditHotMonths);
        batchedMove("audit archive",
                () -> auditLogRepo.archiveOlderThan(auditCutoff, BATCH),
                () -> auditLogRepo.deleteArchivedOlderThan(auditCutoff, BATCH));

        // 3. Successful login history older than 12 months — move to archive
        LocalDateTime lhCutoff = now.minusMonths(loginHistoryHotMonths);
        batchedMove("login-history archive",
                () -> loginHistoryRepo.archiveOlderThan(lhCutoff, BATCH),
                () -> loginHistoryRepo.deleteArchivedOlderThan(lhCutoff, BATCH));

        // 4. Archives older than 24 months total — permanently deleted
        LocalDateTime purgeCutoff = now.minusMonths(totalMonths);
        batched("audit archive purge",
                () -> auditLogArchiveRepo.purgeOlderThan(purgeCutoff, BATCH));
        batched("login-history archive purge",
                () -> loginHistoryArchiveRepo.purgeOlderThan(purgeCutoff, BATCH));

        log.info("Retention: monthly archive/purge finished");
    }

    // ── batch helpers — each batch commits in its own transaction ─────────

    @FunctionalInterface
    private interface BatchStep { int run(); }

    /* Each repo @Modifying method carries its own @Transactional, so every
       batch commits independently — a crash mid-run never loses prior work
       and INSERT IGNORE makes the archive copy safe to re-run. */
    private int step(BatchStep s) {
        return s.run();
    }

    private void batched(String label, BatchStep s) {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                int n = step(s);
                total += n;
                if (n < BATCH) break;
            }
            if (total > 0) log.info("Retention: {} affected {} rows", label, total);
        } catch (Exception e) {
            log.error("Retention: {} failed after {} rows", label, total, e);
        }
    }

    /** Copy a batch into the archive, then delete the same rows — repeat. */
    private void batchedMove(String label, BatchStep copy, BatchStep delete) {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                int copied = step(copy);
                if (copied > 0) step(delete);
                total += copied;
                if (copied < BATCH) break;
            }
            if (total > 0) log.info("Retention: {} moved {} rows", label, total);
        } catch (Exception e) {
            log.error("Retention: {} failed after {} rows", label, total, e);
        }
    }
}
