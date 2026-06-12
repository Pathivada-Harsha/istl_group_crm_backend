package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "task_updates")     
public class TaskUpdateEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private TaskEntity task;

    @Column(name = "work_done", columnDefinition = "TEXT", nullable = false)
    private String workDone;

    @Column(name = "update_type", length = 50)
    private String updateType = "Progress Update";

    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    @Column(name = "new_status", length = 30)
    private String newStatus;

    @Column(name = "status_changed")
    private Boolean statusChanged = false;

    @Column(name = "completion_percent")
    private Integer completionPercent;

    // Time tracking — matches DB columns exactly
    @Column(name = "hours_spent", precision = 6, scale = 2)
    private BigDecimal hoursSpent = BigDecimal.ZERO;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;           // follow-up notes / extra context

    @Column(name = "updated_by")           // DB column: updated_by
    private Long updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;

    @Column(name = "updated_at", nullable = false, insertable = true, updatable = true,
            columnDefinition = "datetime DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    // updatedAt is always set explicitly in TaskService.logDailyUpdate() before save.
    // No @PrePersist needed — the DB column has DEFAULT CURRENT_TIMESTAMP as a safety net
    // but JPA always provides the value explicitly so the default is never used.

    // ── Getters / Setters ──────────────────────────────────────────────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TaskEntity getTask() { return task; }
    public void setTask(TaskEntity task) { this.task = task; }
    public String getWorkDone() { return workDone; }
    public void setWorkDone(String workDone) { this.workDone = workDone; }
    public String getUpdateType() { return updateType; }
    public void setUpdateType(String updateType) { this.updateType = updateType; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public String getNewStatus() { return newStatus; }
    public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
    public Boolean getStatusChanged() { return statusChanged; }
    public void setStatusChanged(Boolean statusChanged) { this.statusChanged = statusChanged; }
    public Integer getCompletionPercent() { return completionPercent; }
    public void setCompletionPercent(Integer completionPercent) { this.completionPercent = completionPercent; }
    public BigDecimal getHoursSpent() { return hoursSpent; }
    public void setHoursSpent(BigDecimal hoursSpent) { this.hoursSpent = hoursSpent; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}