package com.istlgroup.istl_group_crm_backend.scheduler;

import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Module;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Type;
import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;
import com.istlgroup.istl_group_crm_backend.entity.TaskEntity;
import com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo;
import com.istlgroup.istl_group_crm_backend.repo.NotificationRepository;
import com.istlgroup.istl_group_crm_backend.repo.TaskRepository;
import com.istlgroup.istl_group_crm_backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Time-based notifications. Runs every 5 minutes.
 *
 * Generates:
 *   Follow-ups → Due Today, Overdue, Upcoming Reminder (before scheduled time)
 *   Tasks      → Due Today, Overdue
 *
 * DUPLICATE PREVENTION: before raising any time-based notification we check
 * whether the same (userId, type, referenceId) was already raised since the
 * start of today. This guarantees at most one notification per event per day,
 * even though the job fires 288 times a day.
 *
 * @EnableScheduling is already on the main application class.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    /** Follow-ups in these states are "open" and eligible for reminders. */
    private static final List<String> OPEN_FOLLOWUP_STATUSES = List.of("Pending", "Rescheduled");
    /** Tasks in these states are NOT eligible (already done / cancelled). */
    private static final Set<String> CLOSED_TASK_STATUSES = Set.of("completed", "cancelled");
    /** How far ahead a follow-up triggers the "upcoming reminder". */
    private static final long REMINDER_LOOKAHEAD_MINUTES = 30;

    private final FollowupsRepo followupsRepo;
    private final TaskRepository taskRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    @Scheduled(fixedRate = 5 * 60 * 1000)   // every 5 minutes
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime endOfToday = today.atTime(23, 59, 59);

        try { followupReminders(now); }                     catch (Exception e) { log.error("followup reminders failed", e); }
        try { followupDueToday(startOfToday, endOfToday); }  catch (Exception e) { log.error("followup due-today failed", e); }
        try { followupOverdue(startOfToday); }               catch (Exception e) { log.error("followup overdue failed", e); }
        try { taskDueToday(today); }                         catch (Exception e) { log.error("task due-today failed", e); }
        try { taskOverdue(today); }                          catch (Exception e) { log.error("task overdue failed", e); }
    }

    // ── Follow-ups: upcoming reminder (next 30 min) ──────────────────
    private void followupReminders(LocalDateTime now) {
        LocalDateTime windowEnd = now.plusMinutes(REMINDER_LOOKAHEAD_MINUTES);
        List<FollowupsEntity> upcoming = followupsRepo
                .findByStatusInAndScheduledAtBetween(OPEN_FOLLOWUP_STATUSES, now, windowEnd);
        for (FollowupsEntity f : upcoming) {
            raiseIfNew(f.getAssignedTo(), Type.FOLLOWUP_REMINDER, f.getId(),
                    "Follow-up reminder",
                    "You have a " + safe(f.getFollowupType()) + " follow-up coming up soon.",
                    Module.FOLLOWUP);
        }
    }

    // ── Follow-ups: due today ────────────────────────────────────────
    private void followupDueToday(LocalDateTime startOfToday, LocalDateTime endOfToday) {
        List<FollowupsEntity> due = followupsRepo
                .findByStatusInAndScheduledAtBetween(OPEN_FOLLOWUP_STATUSES, startOfToday, endOfToday);
        for (FollowupsEntity f : due) {
            raiseIfNew(f.getAssignedTo(), Type.FOLLOWUP_DUE_TODAY, f.getId(),
                    "Follow-up due today",
                    "A " + safe(f.getFollowupType()) + " follow-up is scheduled for today.",
                    Module.FOLLOWUP);
        }
    }

    // ── Follow-ups: overdue (before today) ───────────────────────────
    private void followupOverdue(LocalDateTime startOfToday) {
        List<FollowupsEntity> overdue = followupsRepo
                .findByStatusInAndScheduledAtBefore(OPEN_FOLLOWUP_STATUSES, startOfToday);
        for (FollowupsEntity f : overdue) {
            raiseIfNew(f.getAssignedTo(), Type.FOLLOWUP_OVERDUE, f.getId(),
                    "Follow-up overdue",
                    "A " + safe(f.getFollowupType()) + " follow-up is overdue. Please take action.",
                    Module.FOLLOWUP);
        }
    }

    // ── Tasks: due today ─────────────────────────────────────────────
    private void taskDueToday(LocalDate today) {
        for (TaskEntity t : taskRepository.findByDueDate(today)) {
            if (isClosed(t.getStatus())) continue;
            raiseIfNew(t.getAssignedTo(), Type.TASK_DUE_TODAY, t.getId(),
                    "Task due today",
                    "Task \"" + safe(t.getTitle()) + "\" is due today.",
                    Module.TASK);
        }
    }

    // ── Tasks: overdue ───────────────────────────────────────────────
    private void taskOverdue(LocalDate today) {
        for (TaskEntity t : taskRepository.findByDueDateBefore(today)) {
            if (isClosed(t.getStatus())) continue;
            raiseIfNew(t.getAssignedTo(), Type.TASK_OVERDUE, t.getId(),
                    "Task overdue",
                    "Task \"" + safe(t.getTitle()) + "\" is overdue.",
                    Module.TASK);
        }
    }

    // ── Duplicate-guarded creation ───────────────────────────────────
    private void raiseIfNew(Long userId, String type, Long referenceId,
                            String title, String message, String module) {
        if (userId == null || referenceId == null) return;
        LocalDateTime since = LocalDate.now().atStartOfDay();   // one-per-event-per-day
        boolean already = notificationRepository
                .existsByUserIdAndNotificationTypeAndReferenceIdAndCreatedAtAfter(
                        userId, type, referenceId, since);
        if (already) return;
        notificationService.createNotification(userId, title, message, module, referenceId, type);
    }

    private boolean isClosed(String status) {
        return status != null && CLOSED_TASK_STATUSES.contains(status.trim().toLowerCase());
    }

    private String safe(String s) { return (s == null || s.isBlank()) ? "scheduled" : s; }
}
