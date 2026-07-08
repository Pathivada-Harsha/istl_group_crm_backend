package com.istlgroup.istl_group_crm_backend.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.service.SessionRegistryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SessionSweeperJob — marks ACTIVE sessions whose last activity exceeded the
 * idle timeout as EXPIRED. Handles users who close the browser without
 * logging out, so the Active Sessions table and the two-device count stay
 * accurate. @EnableScheduling is already on the main application class.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "scheduler.session-sweeper.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SessionSweeperJob {

    private final SessionRegistryService sessionRegistryService;

    /** Every 5 minutes. */
    @Scheduled(cron = "${scheduler.session-sweeper.cron:0 */5 * * * *}")
    public void sweep() {
        try {
            int expired = sessionRegistryService.sweepExpired();
            if (expired > 0) {
                log.info("Session sweeper expired {} idle session(s)", expired);
            }
        } catch (Exception e) {
            log.error("Session sweeper failed", e);
        }
    }
}
