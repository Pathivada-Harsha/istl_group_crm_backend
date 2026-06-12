package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.TaskEntity;
import com.istlgroup.istl_group_crm_backend.entity.TaskUpdateEntity;
import com.istlgroup.istl_group_crm_backend.repo.TaskRepository;
import com.istlgroup.istl_group_crm_backend.repo.TaskUpdateRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Module;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TaskService {

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskUpdateRepository taskUpdateRepository;
    @Autowired private UsersRepo usersRepo;        // ← added for email lookups
    @Autowired private MailService mailService;    // ← added for sending mails
    @Autowired private NotificationService notificationService; // ← in-app notifications

    // ─────────────────────────────────────────────────────────────────────────
    // Task code generator (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

//    private String generateTaskCode() {
//        long next = taskRepository.findMaxTaskNumber() + 1;
//        String code = String.format("TSK-%04d", next);
//        int attempts = 0;
//        while (taskRepository.findByTaskCode(code).isPresent() && attempts < 10) {
//            next++;
//            code = String.format("TSK-%04d", next);
//            attempts++;
//        }
//        return code;
//    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ — all query methods unchanged
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getTasks(Long userId, String userRole,
                                         String search, String status,
                                         String priority, String category,
                                         LocalDate dateFrom, LocalDate dateTo,
                                         int page, int size, Sort sort) {
        Pageable pageable = (sort != null)
                ? PageRequest.of(Math.max(page - 1, 0), size, sort)
                : PageRequest.of(Math.max(page - 1, 0), size);
        String s  = (search   != null && !search.isBlank()                          ) ? search.trim() : null;
        String st = (status   != null && !status.isBlank()   && !"All".equals(status)  ) ? status   : null;
        String pr = (priority != null && !priority.isBlank() && !"All".equals(priority)) ? priority : null;
        String ca = (category != null && !category.isBlank() && !"All".equals(category)) ? category : null;

        Page<TaskEntity> pageResult;
        long total;
        if (userId == null) {
            pageResult = taskRepository.searchAll(s, st, pr, ca, dateFrom, dateTo, pageable);
            total      = taskRepository.countSearchAll(s, st, pr, ca, dateFrom, dateTo);
        } else {
            pageResult = taskRepository.searchByUser(userId, s, st, pr, ca, dateFrom, dateTo, pageable);
            total      = taskRepository.countSearchByUser(userId, s, st, pr, ca, dateFrom, dateTo);
        }

        List<Map<String, Object>> data = pageResult.getContent()
                .stream().map(this::toMapWithUpdates).collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",    true);
        resp.put("data",       data);
        resp.put("total",      total);
        resp.put("page",       page);
        resp.put("size",       size);
        resp.put("totalPages", (int) Math.ceil((double) total / size));
        return resp;
    }

    public Map<String, Object> getTasksForManagerView(Long managerId, Long reportUserId,
                                                       LocalDate dateFrom, LocalDate dateTo,
                                                       String search, int page, int size, Sort sort) {
        Pageable pageable = (sort != null)
                ? PageRequest.of(Math.max(page - 1, 0), size, sort)
                : PageRequest.of(Math.max(page - 1, 0), size);
        String s = (search != null && !search.isBlank()) ? search.trim() : null;
        LocalDateTime fromDt = (dateFrom != null) ? dateFrom.atStartOfDay()               : null;
        LocalDateTime toDt   = (dateTo   != null) ? dateTo.plusDays(1).atStartOfDay()     : null;
        Page<TaskEntity> pageResult = taskRepository.searchManagerTeam(managerId, reportUserId, fromDt, toDt, s, pageable);
        long total = taskRepository.countManagerTeam(managerId, reportUserId, fromDt, toDt, s);
        List<Map<String, Object>> data = pageResult.getContent()
                .stream().map(this::toMapWithUpdates).collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",    true);  resp.put("data",       data);
        resp.put("total",      total); resp.put("page",       page);
        resp.put("size",       size);  resp.put("totalPages", (int) Math.ceil((double) total / size));
        return resp;
    }

    public Map<String, Object> getTasksForTeamView(Long userId, LocalDate dateFrom, LocalDate dateTo,
                                                     String search, int page, int size, Sort sort) {
        Pageable pageable = (sort != null)
                ? PageRequest.of(Math.max(page - 1, 0), size, sort)
                : PageRequest.of(Math.max(page - 1, 0), size);
        String s = (search != null && !search.isBlank()) ? search.trim() : null;
        LocalDateTime fromDt = (dateFrom != null) ? dateFrom.atStartOfDay()           : null;
        LocalDateTime toDt   = (dateTo   != null) ? dateTo.plusDays(1).atStartOfDay() : null;
        Page<TaskEntity> pageResult;
        long total;
        if (userId != null) {
            pageResult = taskRepository.searchTeamByUser(userId, fromDt, toDt, s, pageable);
            total      = taskRepository.countTeamByUser(userId, fromDt, toDt, s);
        } else {
            pageResult = taskRepository.searchTeamAll(fromDt, toDt, s, pageable);
            total      = taskRepository.countTeamAll(fromDt, toDt, s);
        }
        List<Map<String, Object>> data = pageResult.getContent()
                .stream().map(this::toMapWithUpdates).collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",    true);  resp.put("data",       data);
        resp.put("total",      total); resp.put("page",       page);
        resp.put("size",       size);  resp.put("totalPages", (int) Math.ceil((double) total / size));
        return resp;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> createTask(Map<String, Object> body,
                                           Long createdById, String createdByName) {
        TaskEntity task = new TaskEntity();
        // Task code is derived from the DB-generated id (see below).
        // Use a temp placeholder to satisfy NOT NULL constraint.
        task.setTaskCode("__TEMP_TSK_" + System.nanoTime() + "__");
        task.setTitle((String) body.get("title"));
        task.setDescription((String) body.get("description"));
        task.setCategory((String) body.getOrDefault("category", "Other"));
        task.setPriority((String) body.getOrDefault("priority", "Medium"));
        task.setStatus((String) body.getOrDefault("status", "Pending"));
        task.setRelatedTo((String) body.get("relatedTo"));
        task.setProjectId((String) body.get("projectId"));
        task.setProjectName((String) body.get("projectName"));
        task.setOtherContext((String) body.get("otherContext"));

        String dueDateStr = (String) body.get("dueDate");
        if (dueDateStr != null && !dueDateStr.isEmpty())
            task.setDueDate(LocalDate.parse(dueDateStr.substring(0, 10)));

        String startDateStr = (String) body.get("startDate");
        if (startDateStr != null && !startDateStr.isEmpty())
            try { task.setStartedAt(parseDateTimeFlexible(startDateStr)); } catch (Exception ignored) {}

        String endDateStr = (String) body.get("endDate");
        if (endDateStr != null && !endDateStr.isEmpty())
            try { task.setClosedAt(parseDateTimeFlexible(endDateStr)); } catch (Exception ignored) {}

        Object assignedToObj = body.get("assignedTo");
        task.setAssignedTo(assignedToObj != null ? toLong(assignedToObj) : createdById);
        task.setAssignedToName((String) body.getOrDefault("assignedToName", createdByName));
        task.setCreatedBy(createdById);
        task.setCreatedByName(createdByName);

        String estHours = String.valueOf(body.getOrDefault("estimatedHours", ""));
        if (!estHours.isEmpty() && !estHours.equals("null"))
            try { task.setEstimatedHours(new BigDecimal(estHours)); } catch (Exception ignored) {}

        int initialPct = toInt(body.getOrDefault("completionPercent", 0));
        if ("Completed".equals(task.getStatus())) initialPct = 100;
        task.setCompletionPercent(initialPct);
        task.setTotalHoursSpent(BigDecimal.ZERO);

        if (task.getStartedAt() == null
                && ("In Progress".equals(task.getStatus()) || "Completed".equals(task.getStatus())))
            task.setStartedAt(LocalDateTime.now());
        if ("Completed".equals(task.getStatus()) && task.getClosedAt() == null)
            task.setClosedAt(LocalDateTime.now());

        // First save — DB assigns auto-increment id
        TaskEntity saved = taskRepository.save(task);
        // Derive task code from the DB id — only if no real code exists yet.
        String existingTaskCode = saved.getTaskCode();
        if (existingTaskCode == null || existingTaskCode.isBlank() || existingTaskCode.startsWith("__TEMP_")) {
            saved.setTaskCode(String.format("TSK-%04d", saved.getId()));
            saved = taskRepository.save(saved);
        }

        // ── MAIL: notify assignee when task is assigned to someone else ───────
        // Rule: no mail if the creator is assigning the task to themselves
        try {
            if (saved.getAssignedTo() != null
                    && !saved.getAssignedTo().equals(createdById)) {
                sendTaskAssignedMail(saved, createdByName);
            }
        } catch (Exception e) {
            System.err.println("[TaskService] Mail error on create: " + e.getMessage());
        }

        // ── NOTIFICATION (independent of mail; same self-assignment rule) ──
        try {
            if (saved.getAssignedTo() != null
                    && !saved.getAssignedTo().equals(createdById)) {
                notificationService.createNotification(
                    saved.getAssignedTo(),
                    "New task assigned",
                    "You have been assigned the task \"" + saved.getTitle() + "\".",
                    Module.TASK, saved.getId(), Type.TASK_ASSIGNED);
                // Also notify the creator so it appears on their dashboard too.
                notificationService.createNotification(
                    createdById,
                    "Task assigned",
                    "You assigned the task \"" + saved.getTitle() + "\" to "
                        + (saved.getAssignedToName() != null ? saved.getAssignedToName() : "a team member") + ".",
                    Module.TASK, saved.getId(), Type.TASK_ASSIGNED);
            }
        } catch (Exception e) {
            System.err.println("[TaskService] notification error on create: " + e.getMessage());
        }

        // isSelfLog quick-log path (unchanged)
        Object isSelfLog = body.get("isSelfLog");
        if (Boolean.TRUE.equals(isSelfLog) && body.containsKey("workLog")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> wl = (Map<String, Object>) body.get("workLog");
            if (wl != null && wl.containsKey("workDone")) {
                try {
                    logDailyUpdate(saved.getId(), wl, saved.getAssignedTo(), saved.getAssignedToName());
                    saved = taskRepository.findById(saved.getId()).orElse(saved);
                } catch (Exception ignored) {}
            }
        }

        return toMapWithUpdates(saved);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE  (now receives updatedById + updatedByName for reassignment mail)
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> updateTask(Long taskId, Map<String, Object> body,
                                           Long updatedById, String updatedByName) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));

        // Capture old assignee before applying changes
        Long oldAssignedTo = task.getAssignedTo();

        if (body.containsKey("title"))        task.setTitle((String) body.get("title"));
        if (body.containsKey("description"))  task.setDescription((String) body.get("description"));
        if (body.containsKey("category"))     task.setCategory((String) body.get("category"));
        if (body.containsKey("priority"))     task.setPriority((String) body.get("priority"));
        if (body.containsKey("status"))       task.setStatus((String) body.get("status"));
        if (body.containsKey("relatedTo"))    task.setRelatedTo((String) body.get("relatedTo"));
        if (body.containsKey("projectId"))    task.setProjectId((String) body.get("projectId"));
        if (body.containsKey("projectName"))  task.setProjectName((String) body.get("projectName"));
        if (body.containsKey("otherContext")) task.setOtherContext((String) body.get("otherContext"));

        if (body.containsKey("dueDate")) {
            String d = (String) body.get("dueDate");
            task.setDueDate(d != null && !d.isEmpty() ? LocalDate.parse(d.substring(0, 10)) : null);
        }
        if (body.containsKey("startDate")) {
            String s = (String) body.get("startDate");
            if (s != null && !s.isEmpty()) try { task.setStartedAt(parseDateTimeFlexible(s)); } catch (Exception ignored) {}
            else task.setStartedAt(null);
        }
        if (body.containsKey("endDate")) {
            String e = (String) body.get("endDate");
            if (e != null && !e.isEmpty()) try { task.setClosedAt(parseDateTimeFlexible(e)); } catch (Exception ignored) {}
            else task.setClosedAt(null);
        }
        if (body.containsKey("closedAt")) {
            String e = (String) body.get("closedAt");
            if (e != null && !e.isEmpty()) try { task.setClosedAt(parseDateTimeFlexible(e)); } catch (Exception ignored) {}
        }
        if (body.containsKey("assignedTo"))     task.setAssignedTo(toLong(body.get("assignedTo")));
        if (body.containsKey("assignedToName")) task.setAssignedToName((String) body.get("assignedToName"));
        if (body.containsKey("completionPercent")) task.setCompletionPercent(toInt(body.get("completionPercent")));
        if ("Completed".equals(task.getStatus())) task.setCompletionPercent(100);
        if (body.containsKey("estimatedHours")) {
            String eh = String.valueOf(body.get("estimatedHours"));
            if (!eh.isEmpty() && !eh.equals("null"))
                try { task.setEstimatedHours(new BigDecimal(eh)); } catch (Exception ignored) {}
        }

        TaskEntity saved = taskRepository.save(task);

        // ── MAIL: notify new assignee when task is re-assigned to someone else ─
        // Fires when assignedTo has changed AND the new assignee is not the updater
        Long newAssignedTo = saved.getAssignedTo();
        boolean assigneeChanged    = newAssignedTo != null && !newAssignedTo.equals(oldAssignedTo);
        boolean assigneeNotUpdater = newAssignedTo != null && !newAssignedTo.equals(updatedById);

        try {
            if (assigneeChanged && assigneeNotUpdater) {
                sendTaskAssignedMail(saved, updatedByName);
            }
        } catch (Exception e) {
            System.err.println("[TaskService] Mail error on update: " + e.getMessage());
        }

        // ── NOTIFICATION (independent of mail): task reassigned ──
        try {
            if (assigneeChanged && assigneeNotUpdater) {
                notificationService.createNotification(
                    saved.getAssignedTo(),
                    "Task reassigned to you",
                    "The task \"" + saved.getTitle() + "\" has been reassigned to you.",
                    Module.TASK, saved.getId(), Type.TASK_REASSIGNED);
            }
        } catch (Exception e) {
            System.err.println("[TaskService] notification error on update: " + e.getMessage());
        }

        return toMapWithUpdates(saved);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOG DAILY UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> logDailyUpdate(Long taskId, Map<String, Object> body,
                                               Long updatedById, String updatedByName) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));

        String oldStatus = task.getStatus();
        String newStatus = (String) body.getOrDefault("newStatus", oldStatus);
        int pct = toInt(body.getOrDefault("completionPercent", task.getCompletionPercent()));
        if ("Completed".equals(newStatus)) pct = 100;

        TaskUpdateEntity update = new TaskUpdateEntity();
        update.setHoursSpent(BigDecimal.ZERO);
        update.setTask(task);
        update.setWorkDone((String) body.get("workDone"));
        update.setUpdateType((String) body.getOrDefault("updateType", "Progress Update"));
        update.setBlockedReason((String) body.get("blockedReason"));
        update.setNotes((String) body.get("notes"));
        update.setNewStatus(newStatus);
        update.setStatusChanged(!oldStatus.equals(newStatus));
        update.setCompletionPercent(pct);
        update.setUpdatedBy(updatedById);
        update.setUpdatedByName(updatedByName);

        String logDateStr = (String) body.get("logDate");
        System.out.println("[TaskService] logDate received: " + logDateStr);
        if (logDateStr != null && !logDateStr.isBlank()) {
            try {
                String startTimeStr2 = (String) body.get("startTime");
                String timeStr = (startTimeStr2 != null && startTimeStr2.length() >= 5)
                        ? startTimeStr2.substring(0, 5) + ":00" : "00:00:00";
                String dateTimeStr = logDateStr.substring(0, 10) + "T" + timeStr;
                System.out.println("[TaskService] Parsed updatedAt: " + dateTimeStr);
                update.setUpdatedAt(LocalDateTime.parse(dateTimeStr));
            } catch (Exception e) {
                System.out.println("[TaskService] Parse failed: " + e.getMessage() + ", using now()");
                update.setUpdatedAt(LocalDateTime.now());
            }
        } else {
            System.out.println("[TaskService] No logDate, using now()");
            update.setUpdatedAt(LocalDateTime.now());
        }

        String endTimeStr   = (String) body.get("endTime");
        String startTimeStr = (String) body.get("startTime");
        if (startTimeStr != null && !startTimeStr.isEmpty())
            try { update.setStartTime(LocalTime.parse(startTimeStr)); } catch (Exception ignored) {}
        if (endTimeStr   != null && !endTimeStr.isEmpty())
            try { update.setEndTime(LocalTime.parse(endTimeStr));     } catch (Exception ignored) {}

        BigDecimal hours = BigDecimal.ZERO;
        Object hoursObj = body.get("hoursSpent");
        if (hoursObj != null) try { hours = new BigDecimal(hoursObj.toString()); } catch (Exception ignored) {}
        update.setHoursSpent(hours);

        taskUpdateRepository.save(update);

        task.setStatus(newStatus);
        task.setCompletionPercent("Completed".equals(newStatus) ? 100 : pct);
        task.setTotalHoursSpent(
                (task.getTotalHoursSpent() != null ? task.getTotalHoursSpent() : BigDecimal.ZERO).add(hours));
        if (task.getStartedAt() == null
                && ("In Progress".equals(newStatus) || "Completed".equals(newStatus)))
            task.setStartedAt(LocalDateTime.now());
        if ("Completed".equals(newStatus) && task.getClosedAt() == null)
            task.setClosedAt(LocalDateTime.now());

        TaskEntity saved = taskRepository.save(task);

        // ── MAIL: notify the task creator when the assignee logs a status update ─
        // Rule: only send when the person logging the update IS the assigned person
        //       AND the creator is a different person (no self-notification)
        try {
            boolean updaterIsAssignee = updatedById != null
                    && updatedById.equals(saved.getAssignedTo());
            boolean creatorIsDifferent = saved.getCreatedBy() != null
                    && !saved.getCreatedBy().equals(updatedById);

            if (updaterIsAssignee && creatorIsDifferent) {
                sendTaskStatusUpdateMail(saved, update, updatedByName);
            }
        } catch (Exception e) {
            System.err.println("[TaskService] Mail error on logUpdate: " + e.getMessage());
        }

        // ── NOTIFICATION: task completed → notify the task creator ──
        try {
            if ("Completed".equals(newStatus) && !"Completed".equals(oldStatus)
                    && saved.getCreatedBy() != null
                    && !saved.getCreatedBy().equals(updatedById)) {
                notificationService.createNotification(
                    saved.getCreatedBy(),
                    "Task completed",
                    "\"" + saved.getTitle() + "\" was marked completed by " + updatedByName + ".",
                    Module.TASK, saved.getId(), Type.TASK_COMPLETED);
            }
        } catch (Exception e) {
            System.err.println("[TaskService] notification error on completion: " + e.getMessage());
        }

        return toMapWithUpdates(saved);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIL — Task assigned to someone
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sent to the assignee when a task is created for them or re-assigned to them.
     * Not sent when a user assigns a task to themselves.
     */
    private void sendTaskAssignedMail(TaskEntity task, String assignedByName) {
        String toEmail = usersRepo.findUserMailWithUserId(task.getAssignedTo());
        if (toEmail == null || toEmail.isBlank()) return;

        String subject = "New Task Assigned By " + assignedByName;

        String dueDate     = task.getDueDate()    != null ? task.getDueDate().toString()    : "N/A";
        String projectName = task.getProjectName() != null ? task.getProjectName()          : "N/A";
        String description = task.getDescription() != null ? task.getDescription()          : "";

        String body = """
                <html>
                  <body style="margin:0;padding:0;background-color:#f4f6f8;font-family:Arial,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:20px 0;">
                      <tr><td align="center">

                        <table width="600" cellpadding="0" cellspacing="0"
                               style="background:#ffffff;border-radius:8px;
                                      box-shadow:0 2px 8px rgba(0,0,0,0.08);padding:30px;">

                          <!-- Header -->
                          <tr>
                            <td align="center" style="padding-bottom:20px;">
                              <h2 style="margin:0;color:#2E86C1;">New Task Assigned By %s</h2>
                            </td>
                          </tr>

                          <!-- Greeting -->
                          <tr>
                            <td style="font-size:16px;color:#333;padding-bottom:15px;">
                              Hello <b>%s</b>,
                            </td>
                          </tr>

                          <!-- Message -->
                          <tr>
                            <td style="font-size:15px;color:#555;padding-bottom:20px;">
                              A new task has been assigned to you. Please find the details below.
                            </td>
                          </tr>

                          <!-- Details -->
                          <tr>
                            <td>
                              <table width="100%%" cellpadding="8" cellspacing="0"
                                     style="background:#f9fafb;border-radius:6px;font-size:14px;color:#444;">
                                <tr>
                                  <td width="40%%"><b>Task Code</b></td>
                                  <td>%s</td>
                                </tr>
                                <tr>
                                  <td><b>Title</b></td>
                                  <td><b>%s</b></td>
                                </tr>
                                <tr>
                                  <td><b>Priority</b></td>
                                  <td style="color:#d35400;"><b>%s</b></td>
                                </tr>
                                <tr>
                                  <td><b>Status</b></td>
                                  <td style="color:#27ae60;"><b>%s</b></td>
                                </tr>
                                <tr>
                                  <td><b>Category</b></td>
                                  <td>%s</td>
                                </tr>
                                <tr>
                                  <td><b>Due Date</b></td>
                                  <td>%s</td>
                                </tr>
                                <tr>
                                  <td><b>Project</b></td>
                                  <td>%s</td>
                                </tr>
                              </table>
                            </td>
                          </tr>

                          <!-- Description -->
                          <tr>
                            <td style="padding-top:20px;font-size:14px;color:#555;">
                              <b>Description:</b>
                              <div style="margin-top:8px;padding:10px;background:#f1f1f1;border-radius:5px;">
                                %s
                              </div>
                            </td>
                          </tr>

                          <!-- Footer -->
                          <tr>
                            <td style="padding-top:30px;font-size:13px;color:#888;text-align:center;">
                              Regards,<br><b>SESOLA CRM System</b><br><br>
                              <a href="https://crm.sesolaenergy.com/"
                                 style="color:#2E86C1;text-decoration:none;font-size:12px;font-weight:600;">
                                &#128279; Open CRM Portal
                              </a>
                            </td>
                          </tr>

                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(
                assignedByName,
                task.getAssignedToName(),
                task.getTaskCode(),
                task.getTitle(),
                task.getPriority(),
                task.getStatus(),
                task.getCategory(),
                dueDate,
                projectName,
                description
        );

        mailService.sendEmail(toEmail, subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIL — Assignee logged a status update → notify the task creator
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sent to the task creator when the assigned person logs a daily update.
     * Not sent if the creator and assignee are the same person.
     */
    private void sendTaskStatusUpdateMail(TaskEntity task, TaskUpdateEntity update,
                                           String updatedByName) {
        String toEmail = usersRepo.findUserMailWithUserId(task.getCreatedBy());
        if (toEmail == null || toEmail.isBlank()) return;

        String subject = "Task Update By " + updatedByName + " — " + task.getTaskCode();

        String workDone     = update.getWorkDone()      != null ? update.getWorkDone()      : "—";
        String notes        = update.getNotes()         != null ? update.getNotes()          : "—";
        String blockedReason = update.getBlockedReason() != null ? update.getBlockedReason() : "—";
        String newStatus    = update.getNewStatus()     != null ? update.getNewStatus()     : task.getStatus();
        String statusColor  = "Completed".equals(newStatus) ? "#27ae60"
                            : "In Progress".equals(newStatus) ? "#2E86C1"
                            : "Blocked".equals(newStatus) ? "#c0392b"
                            : "#888888";

        String body = """
                <html>
                  <body style="margin:0;padding:0;background-color:#f4f6f8;font-family:Arial,sans-serif;">
                    <table width="100%%" cellpadding="0" cellspacing="0" style="padding:20px 0;">
                      <tr><td align="center">

                        <table width="600" cellpadding="0" cellspacing="0"
                               style="background:#ffffff;border-radius:8px;
                                      box-shadow:0 2px 8px rgba(0,0,0,0.08);padding:30px;">

                          <!-- Header -->
                          <tr>
                            <td align="center" style="padding-bottom:20px;">
                              <h2 style="margin:0;color:#2E86C1;">Task Status Update By %s</h2>
                            </td>
                          </tr>

                          <!-- Greeting -->
                          <tr>
                            <td style="font-size:16px;color:#333;padding-bottom:15px;">
                              Hello <b>%s</b>,
                            </td>
                          </tr>

                          <!-- Message -->
                          <tr>
                            <td style="font-size:15px;color:#555;padding-bottom:20px;">
                              <b>%s</b> has logged an update on task
                              <b style="color:#2E86C1;">%s — %s</b>.
                            </td>
                          </tr>

                          <!-- Task Summary -->
                          <tr>
                            <td>
                              <table width="100%%" cellpadding="8" cellspacing="0"
                                     style="background:#f9fafb;border-radius:6px;font-size:14px;color:#444;">
                                <tr>
                                  <td width="40%%"><b>Task Code</b></td>
                                  <td>%s</td>
                                </tr>
                                <tr>
                                  <td><b>Title</b></td>
                                  <td><b>%s</b></td>
                                </tr>
                                <tr>
                                  <td><b>Current Status</b></td>
                                  <td style="color:%s;"><b>%s</b></td>
                                </tr>
                                <tr>
                                  <td><b>Completion</b></td>
                                  <td><b>%s%%</b></td>
                                </tr>
                                <tr>
                                  <td><b>Update Type</b></td>
                                  <td>%s</td>
                                </tr>
                                <tr>
                                  <td><b>Hours Logged</b></td>
                                  <td>%s hrs</td>
                                </tr>
                              </table>
                            </td>
                          </tr>

                          <!-- Work Done -->
                          <tr>
                            <td style="padding-top:20px;font-size:14px;color:#555;">
                              <b>Work Done:</b>
                              <div style="margin-top:8px;padding:10px;background:#f1f1f1;border-radius:5px;">
                                %s
                              </div>
                            </td>
                          </tr>

                          <!-- Blocked Reason (only show if blocked) -->
                          %s

                          <!-- Notes -->
                          <tr>
                            <td style="padding-top:16px;font-size:14px;color:#555;">
                              <b>Notes:</b>
                              <div style="margin-top:8px;padding:10px;background:#f1f1f1;border-radius:5px;">
                                %s
                              </div>
                            </td>
                          </tr>

                          <!-- Footer -->
                          <tr>
                            <td style="padding-top:30px;font-size:13px;color:#888;text-align:center;">
                              Regards,<br><b>SESOLA CRM System</b><br><br>
                              <a href="https://crm.sesolaenergy.com/"
                                 style="color:#2E86C1;text-decoration:none;font-size:12px;font-weight:600;">
                                &#128279; Open CRM Portal
                              </a>
                            </td>
                          </tr>

                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(
                updatedByName,                          // header: "Task Status Update By X"
                task.getCreatedByName(),                // greeting: "Hello Y"
                updatedByName,                          // message: "X has logged an update"
                task.getTaskCode(), task.getTitle(),    // message: task reference
                task.getTaskCode(),                     // table: task code
                task.getTitle(),                        // table: title
                statusColor,                            // table: status colour
                newStatus,                              // table: status value
                update.getCompletionPercent(),          // table: completion %
                update.getUpdateType(),                 // table: update type
                update.getHoursSpent(),                 // table: hours
                workDone,                               // work done box
                // Blocked reason row — only rendered when reason is present
                (update.getBlockedReason() != null && !update.getBlockedReason().isBlank())
                    ? """
                      <tr>
                        <td style="padding-top:16px;font-size:14px;color:#c0392b;">
                          <b>Blocked Reason:</b>
                          <div style="margin-top:8px;padding:10px;background:#fff0f0;
                                      border-left:3px solid #c0392b;border-radius:5px;">
                            %s
                          </div>
                        </td>
                      </tr>
                      """.formatted(blockedReason)
                    : "",
                notes                                   // notes box
        );

        mailService.sendEmail(toEmail, subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE / DETAIL (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    public void deleteTask(Long taskId) { taskRepository.deleteById(taskId); }

    public Map<String, Object> getTaskDetail(Long taskId) {
        return toMapWithUpdates(taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERTERS (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(TaskEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId()); m.put("taskCode", t.getTaskCode()); m.put("title", t.getTitle());
        m.put("description", t.getDescription()); m.put("category", t.getCategory());
        m.put("priority", t.getPriority()); m.put("status", t.getStatus());
        m.put("dueDate", t.getDueDate() != null ? t.getDueDate().toString() : null);
        m.put("projectId", t.getProjectId()); m.put("projectName", t.getProjectName());
        m.put("otherContext", t.getOtherContext());
        m.put("assignedTo", t.getAssignedTo()); m.put("assignedToName", t.getAssignedToName());
        m.put("createdBy",  t.getCreatedBy());  m.put("createdByName",  t.getCreatedByName());
        m.put("relatedTo",  t.getRelatedTo());
        m.put("completionPercent", t.getCompletionPercent());
        m.put("estimatedHours",    t.getEstimatedHours());
        m.put("totalHoursSpent",   t.getTotalHoursSpent());
        String startedAt = t.getStartedAt() != null ? t.getStartedAt().toString() : null;
        String closedAt  = t.getClosedAt()  != null ? t.getClosedAt().toString()  : null;
        m.put("startedAt", startedAt); m.put("startDate", startedAt);
        m.put("closedAt",  closedAt);  m.put("endDate",   closedAt);
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("updatedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toMapWithUpdates(TaskEntity t) {
        Map<String, Object> m = toMap(t);
        List<Map<String, Object>> updates = taskUpdateRepository
                .findByTaskIdOrderByUpdatedAtDesc(t.getId()).stream().map(u -> {
                    Map<String, Object> um = new LinkedHashMap<>();
                    um.put("id",                u.getId());
                    um.put("workDone",          u.getWorkDone());
                    um.put("updateType",        u.getUpdateType());
                    um.put("blockedReason",     u.getBlockedReason());
                    um.put("newStatus",         u.getNewStatus());
                    um.put("statusChanged",     u.getStatusChanged());
                    um.put("completionPercent", u.getCompletionPercent());
                    um.put("hoursSpent",        u.getHoursSpent());
                    um.put("startTime", u.getStartTime() != null ? u.getStartTime().toString() : null);
                    um.put("endTime",   u.getEndTime()   != null ? u.getEndTime().toString()   : null);
                    um.put("notes",         u.getNotes());
                    um.put("updatedByName", u.getUpdatedByName());
                    um.put("updatedAt", u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : null);
                    return um;
                }).collect(Collectors.toList());
        m.put("updates", updates);
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILS (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Long)    return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return null; }
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Long)    return ((Long) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private LocalDateTime parseDateTimeFlexible(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
        if (s.length() == 16) return LocalDateTime.parse(s + ":00");
        if (s.length() >= 19) return LocalDateTime.parse(s.substring(0, 19));
        return LocalDateTime.parse(s);
    }
}