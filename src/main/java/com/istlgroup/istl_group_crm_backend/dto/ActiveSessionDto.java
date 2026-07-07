package com.istlgroup.istl_group_crm_backend.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * ActiveSessionDto — safe projection of user_sessions for the UI.
 * Deliberately excludes session_hash and device_fingerprint.
 */
@Data
public class ActiveSessionDto {

    private Long id;
    private Long userId;
    private String employeeId;
    private String username;
    private String email;
    private String ipAddress;
    private String browser;
    private String browserVersion;
    private String operatingSystem;
    private String deviceType;
    private String deviceName;
    private String city;
    private String state;
    private String country;
    private LocalDateTime loginAt;
    private LocalDateTime lastSeenAt;
    private String status;

    /** True when this row belongs to the session making the request. */
    private boolean currentSession;
}
