package com.istlgroup.istl_group_crm_backend.dto;

import java.util.List;

import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;
import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryEntity;

import lombok.Data;

/** Payload for the user details drawer (Feature 5). */
@Data
public class UserActivitySummaryDto {

    private Long userId;
    private String employeeId;
    private String name;
    private String email;
    private String phone;
    private String role;
    private String designation;
    private String team;
    private Long isActive;

    private long totalLoginCount;
    private Long avgSessionDurationSec;
    private String mostUsedDevice;
    private String mostUsedBrowser;
    private String mostUsedLocation;

    private List<ActiveSessionDto> activeSessions;
    private List<LoginHistoryEntity> recentLogins;
    private List<AuditLogEntity> recentActivities;
}
