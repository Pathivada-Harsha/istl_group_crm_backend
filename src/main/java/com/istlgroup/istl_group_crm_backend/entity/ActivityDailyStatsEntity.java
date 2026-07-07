package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "activity_daily_stats")
@Data
public class ActivityDailyStatsEntity {

    @Id
    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "total_logins")
    private Long totalLogins = 0L;

    @Column(name = "failed_logins")
    private Long failedLogins = 0L;

    @Column(name = "unique_users")
    private Long uniqueUsers = 0L;

    @Column(name = "unique_devices")
    private Long uniqueDevices = 0L;

    @Column(name = "unique_locations")
    private Long uniqueLocations = 0L;

    @Column(name = "total_activities")
    private Long totalActivities = 0L;

    @Column(name = "peak_active_sessions")
    private Long peakActiveSessions = 0L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
