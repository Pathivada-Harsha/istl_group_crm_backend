package com.istlgroup.istl_group_crm_backend.scheduler;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.service.LeadHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runs daily at 08:00 and finds all NOT_RESPONDED leads
 * whose telecallerStatusUpdatedAt is older than 7 days.
 *
 * Action: resets telecallerStatus → PENDING so the lead
 * floats back to the top of the telecaller's queue.
 *
 * Enable scheduling in your Spring Boot main class:
 *   @EnableScheduling
 */
@Component
@Slf4j
public class NotRespondedRequeueJob {

    private static final int REQUEUE_AFTER_DAYS = 7;

    @Autowired private LeadsRepo          leadsRepo;
    @Autowired private LeadHistoryService  leadHistoryService;

    /**
     * Runs every day at 08:00 server time.
     * Cron: second minute hour day month weekday
     */
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void requeueNotRespondedLeads() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(REQUEUE_AFTER_DAYS);

        List<LeadsEntity> staleLeads =
            leadsRepo.findNotRespondedLeadsOlderThan(cutoff);

        if (staleLeads.isEmpty()) {
            log.info("[RequeueJob] No stale NOT_RESPONDED leads found.");
            return;
        }

        log.info("[RequeueJob] Re-queuing {} NOT_RESPONDED leads older than {} days",
                 staleLeads.size(), REQUEUE_AFTER_DAYS);

        for (LeadsEntity lead : staleLeads) {
            String previousStatus = lead.getTelecallerStatus();
            lead.setTelecallerStatus("PENDING");
            lead.setTelecallerStatusUpdatedAt(LocalDateTime.now());
            leadsRepo.save(lead);

            try {
                leadHistoryService.addHistory(
                    lead.getId(),
                    "TELECALLER_REQUEUED",
                    "telecallerStatus",
                    previousStatus,
                    "PENDING",
                    "Lead re-queued automatically after 7 days of no response",
                    null   // system action, no userId
                );
            } catch (Exception e) {
                log.warn("[RequeueJob] Failed to write history for lead {}: {}",
                         lead.getId(), e.getMessage());
            }
        }

        log.info("[RequeueJob] Done. {} leads reset to PENDING.", staleLeads.size());
    }
}