package com.istlgroup.istl_group_crm_backend.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class DashboardStatsDto {

    private long totalLoginsToday;
    private long failedLoginsToday;
    private long activeSessions;
    private long uniqueDevicesToday;
    private long uniqueLocationsToday;
    private long totalActivitiesToday;

    /** [{userId, username, count}] — top 5 by activity count today. */
    private List<Map<String, Object>> mostActiveUsers;

    /** [{date, count}] — successful logins per day, last 7 days. */
    private List<Map<String, Object>> loginTrend;

    /** [{deviceType, count}] — successful logins per device type, last 7 days. */
    private List<Map<String, Object>> deviceSplit;
}
