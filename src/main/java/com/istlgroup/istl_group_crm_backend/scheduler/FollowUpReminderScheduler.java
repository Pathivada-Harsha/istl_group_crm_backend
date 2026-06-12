package com.istlgroup.istl_group_crm_backend.scheduler;

import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class FollowUpReminderScheduler {

    private final FollowupsRepo followupsRepo;
    private final UsersRepo usersRepo;
    private final MailService mailService;

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a");

    /**
     * Runs every day at 10:00 AM.
     * Fetches all Pending and Rescheduled follow-ups, groups them by assigned user,
     * and sends one consolidated reminder email per user.
     */
    
//    @Scheduled(cron = "0 45 12 * * *")
    @Scheduled(cron = "0 0 10 * * *")
    public void sendFollowUpReminders() {
        log.info("FollowUpReminderScheduler: Starting daily follow-up reminder emails at 10 AM");

        try {
            // Fetch all followups with status Pending or Rescheduled
            List<FollowupsEntity> pendingFollowups =
                    followupsRepo.findAllPendingOrRescheduledFollowups();

            if (pendingFollowups == null || pendingFollowups.isEmpty()) {
                log.info("FollowUpReminderScheduler: No pending or rescheduled follow-ups found. Skipping.");
                return;
            }

            log.info("FollowUpReminderScheduler: Found {} pending/rescheduled follow-ups", pendingFollowups.size());

            // Group follow-ups by assigned_to (user ID)
            Map<Long, List<FollowupsEntity>> followupsByUser = pendingFollowups.stream()
                    .filter(f -> f.getAssignedTo() != null)
                    .collect(Collectors.groupingBy(FollowupsEntity::getAssignedTo));

            int emailsSent = 0;
            int emailsFailed = 0;

            for (Map.Entry<Long, List<FollowupsEntity>> entry : followupsByUser.entrySet()) {
                Long userId = entry.getKey();
                List<FollowupsEntity> userFollowups = entry.getValue();

                try {
                    // Fetch user details from users table using assigned_to ID
                    Optional<UsersEntity> userOpt = usersRepo.findById(userId);

                    if (userOpt.isEmpty()) {
                        log.warn("FollowUpReminderScheduler: User not found for ID: {}. Skipping.", userId);
                        continue;
                    }

                    UsersEntity user = userOpt.get();

                    if (user.getEmail() == null || user.getEmail().isBlank()) {
                        log.warn("FollowUpReminderScheduler: No email found for user ID: {} ({}). Skipping.",
                                userId, user.getName());
                        continue;
                    }

                    // Build and send the email
                    String subject = buildEmailSubject(userFollowups.size());
                    String body = buildEmailBody(user.getName(), userFollowups);

                    mailService.sendEmail(user.getEmail(), subject, body);
                    emailsSent++;

                    log.info("FollowUpReminderScheduler: Reminder sent to {} ({}) for {} follow-up(s)",
                            user.getName(), user.getEmail(), userFollowups.size());

                } catch (Exception e) {
                    emailsFailed++;
                    log.error("FollowUpReminderScheduler: Failed to send reminder for user ID: {}. Error: {}",
                            userId, e.getMessage());
                }
            }

            log.info("FollowUpReminderScheduler: Completed. Emails sent: {}, Failed: {}", emailsSent, emailsFailed);

        } catch (Exception e) {
            log.error("FollowUpReminderScheduler: Unexpected error during reminder job", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: Email Subject
    // ─────────────────────────────────────────────────────────────────────────

    private String buildEmailSubject(int count) {
        return "🔔 Reminder: You have " + count + " pending follow-up" + (count > 1 ? "s" : "") + " – ISTL CRM";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: Email Body (HTML)
    // ─────────────────────────────────────────────────────────────────────────

    private String buildEmailBody(String userName, List<FollowupsEntity> followups) {
        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html>")
          .append("<html lang='en'><head><meta charset='UTF-8'>")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
          .append("<title>Follow-up Reminder</title></head>")
          .append("<body style='margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,sans-serif;'>")
          .append("<table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f4f6f9;padding:30px 0;'>")
          .append("<tr><td align='center'>")
          .append("<table width='620' cellpadding='0' cellspacing='0' style='background:#ffffff;border-radius:8px;")
          .append("box-shadow:0 2px 8px rgba(0,0,0,0.1);overflow:hidden;'>")

          // Header
          .append("<tr><td style='background-color:#1a3c6e;padding:24px 32px;text-align:center;'>")
          .append("<h1 style='margin:0;color:#ffffff;font-size:22px;letter-spacing:0.5px;'>ISTL CRM</h1>")
          .append("<p style='margin:6px 0 0;color:#a8c4e8;font-size:13px;'>Daily Follow-up Reminder</p>")
          .append("</td></tr>")

          // Greeting
          .append("<tr><td style='padding:28px 32px 12px;'>")
          .append("<p style='margin:0;font-size:15px;color:#333333;'>Hello <strong>")
          .append(escapeHtml(userName))
          .append("</strong>,</p>")
          .append("<p style='margin:12px 0 0;font-size:14px;color:#555555;line-height:1.6;'>")
          .append("This is your daily reminder. You have <strong>")
          .append(followups.size())
          .append("</strong> follow-up")
          .append(followups.size() > 1 ? "s" : "")
          .append(" that ")
          .append(followups.size() > 1 ? "are" : "is")
          .append(" currently <strong>Pending</strong> or <strong>Rescheduled</strong> and require your attention.")
          .append("</p></td></tr>")

          // Table Header
          .append("<tr><td style='padding:16px 32px 8px;'>")
          .append("<table width='100%' cellpadding='0' cellspacing='0' ")
          .append("style='border-collapse:collapse;font-size:13px;'>")
          .append("<thead>")
          .append("<tr style='background-color:#1a3c6e;color:#ffffff;'>")
          .append("<th style='padding:10px 12px;text-align:left;border-radius:4px 0 0 0;'>#</th>")
          .append("<th style='padding:10px 12px;text-align:left;'>Scheduled At</th>")
          .append("<th style='padding:10px 12px;text-align:left;'>Type</th>")
          .append("<th style='padding:10px 12px;text-align:left;'>Priority</th>")
          .append("<th style='padding:10px 12px;text-align:left;border-radius:0 4px 0 0;'>Status</th>")
          .append("</tr>")
          .append("</thead><tbody>");

        // Table Rows — show only latest 10
        int displayLimit = Math.min(10, followups.size());
        for (int i = 0; i < displayLimit; i++) {
            FollowupsEntity f = followups.get(i);
            String rowBg = (i % 2 == 0) ? "#f9fbfd" : "#ffffff";
            String priorityColor = getPriorityColor(f.getPriority());
            String statusColor = getStatusColor(f.getStatus());

            sb.append("<tr style='background-color:").append(rowBg).append(";'>")
              .append("<td style='padding:10px 12px;color:#555;border-bottom:1px solid #eee;'>").append(i + 1).append("</td>")
              .append("<td style='padding:10px 12px;color:#333;border-bottom:1px solid #eee;'>")
              .append(f.getScheduledAt() != null ? f.getScheduledAt().format(DISPLAY_FORMAT) : "N/A")
              .append("</td>")
              .append("<td style='padding:10px 12px;color:#333;border-bottom:1px solid #eee;'>")
              .append(escapeHtml(f.getFollowupType() != null ? f.getFollowupType() : "N/A"))
              .append("</td>")
              .append("<td style='padding:10px 12px;border-bottom:1px solid #eee;'>")
              .append("<span style='background-color:").append(priorityColor).append(";color:#fff;")
              .append("padding:3px 8px;border-radius:12px;font-size:11px;font-weight:bold;'>")
              .append(escapeHtml(f.getPriority() != null ? f.getPriority() : "N/A"))
              .append("</span></td>")
              .append("<td style='padding:10px 12px;border-bottom:1px solid #eee;'>")
              .append("<span style='background-color:").append(statusColor).append(";color:#fff;")
              .append("padding:3px 8px;border-radius:12px;font-size:11px;font-weight:bold;'>")
              .append(escapeHtml(f.getStatus() != null ? f.getStatus() : "N/A"))
              .append("</span></td>")
              .append("</tr>");

            // Notes row if available
            if (f.getNotes() != null && !f.getNotes().isBlank()) {
                sb.append("<tr style='background-color:").append(rowBg).append(";'>")
                  .append("<td colspan='5' style='padding:4px 12px 10px 24px;color:#777;font-size:12px;")
                  .append("border-bottom:1px solid #eee;font-style:italic;'>📝 Note: ")
                  .append(escapeHtml(f.getNotes()))
                  .append("</td></tr>");
            }
        }

        sb.append("</tbody></table>");

        // Show remaining count note if total exceeds 10
        if (followups.size() > 10) {
            int remaining = followups.size() - 10;
            sb.append("<p style='margin:10px 0 0;font-size:13px;color:#555555;'>")
              .append("Showing <strong>10</strong> of <strong>").append(followups.size()).append("</strong> follow-ups. ")
              .append("<strong>").append(remaining).append(" more</strong> follow-up").append(remaining > 1 ? "s" : "")
              .append(" can be viewed in the CRM dashboard.")
              .append("</p>");
        }

        sb.append("</td></tr>")

          // Footer note
          .append("<tr><td style='padding:20px 32px 28px;'>")
          .append("<p style='margin:0;font-size:13px;color:#777777;line-height:1.6;'>")
          .append("Please log in to <a href='https://crm.sesolaenergy.com/' style='color:#1a3c6e;font-weight:bold;text-decoration:none;'>ISTL CRM</a> to update or complete your follow-ups.")
          .append("</p></td></tr>")

          // Footer
          .append("<tr><td style='background-color:#f0f4f9;padding:16px 32px;text-align:center;")
          .append("border-top:1px solid #e0e7ef;'>")
          .append("<p style='margin:0;font-size:11px;color:#999999;'>")
          .append("This is an automated reminder from ISTL CRM. Please do not reply to this email.")
          .append("</p></td></tr>")

          .append("</table>")
          .append("</td></tr></table>")
          .append("</body></html>");

        return sb.toString();
    }

    private String getPriorityColor(String priority) {
        if (priority == null) return "#888888";
        return switch (priority.toLowerCase()) {
            case "high"   -> "#e53935";
            case "medium" -> "#fb8c00";
            case "low"    -> "#43a047";
            default       -> "#888888";
        };
    }

    private String getStatusColor(String status) {
        if (status == null) return "#888888";
        return switch (status.toLowerCase()) {
            case "pending"     -> "#f59e0b";
            case "rescheduled" -> "#3b82f6";
            default            -> "#888888";
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
}