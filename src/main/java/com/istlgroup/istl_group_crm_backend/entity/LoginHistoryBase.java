package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;

/**
 * LoginHistoryBase — shared columns for login_history and its archive twin.
 * Append-only: rows are only ever inserted (logout_at is the single update).
 */
@MappedSuperclass
@Data
public abstract class LoginHistoryBase {

    public static final String STATUS_SUCCESS                 = "SUCCESS";
    public static final String STATUS_FAILED_INVALID_PASSWORD = "FAILED_INVALID_PASSWORD";
    public static final String STATUS_FAILED_USER_NOT_FOUND   = "FAILED_USER_NOT_FOUND";
    public static final String STATUS_FAILED_ACCOUNT_INACTIVE = "FAILED_ACCOUNT_INACTIVE";
    public static final String STATUS_FAILED_ACCOUNT_LOCKED   = "FAILED_ACCOUNT_LOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "username_entered")
    private String usernameEntered;

    private String username;

    private String email;

    @Column(name = "session_row_id")
    private Long sessionRowId;

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt;

    @Column(name = "logout_at")
    private LocalDateTime logoutAt;

    @Column(name = "session_duration_sec")
    private Long sessionDurationSec;

    @Column(name = "login_status", length = 40, nullable = false)
    private String loginStatus;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(length = 60)
    private String browser;

    @Column(name = "browser_version", length = 30)
    private String browserVersion;

    @Column(name = "operating_system", length = 60)
    private String operatingSystem;

    @Column(name = "device_type", length = 20)
    private String deviceType;

    @Column(name = "device_name", length = 120)
    private String deviceName;

    @Column(name = "screen_resolution", length = 20)
    private String screenResolution;

    @Column(name = "time_zone", length = 60)
    private String timeZone;

    @Column(length = 80)
    private String country;

    @Column(length = 80)
    private String state;

    @Column(length = 80)
    private String city;

    private Double latitude;

    private Double longitude;
}
