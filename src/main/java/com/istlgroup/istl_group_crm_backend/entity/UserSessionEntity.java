package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * UserSessionEntity — live session registry.
 * One row per login session. The SessionFilter consults this table on every
 * request, which is what makes the two-device limit and remote logout work.
 */
@Entity
@Table(name = "user_sessions")
@Data
public class UserSessionEntity {

    public static final String STATUS_ACTIVE            = "ACTIVE";
    public static final String STATUS_LOGGED_OUT        = "LOGGED_OUT";
    public static final String STATUS_EXPIRED           = "EXPIRED";
    public static final String STATUS_EVICTED           = "EVICTED";
    public static final String STATUS_ADMIN_TERMINATED  = "ADMIN_TERMINATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 of the HTTP session id — the raw session id is never stored. */
    @Column(name = "session_hash", length = 64, nullable = false, unique = true)
    private String sessionHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "employee_id")
    private String employeeId;

    private String username;

    private String email;

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

    @Column(name = "device_fingerprint", length = 64)
    private String deviceFingerprint;

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

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "logout_at")
    private LocalDateTime logoutAt;

    @Column(length = 20, nullable = false)
    private String status = STATUS_ACTIVE;

    @Column(name = "logout_reason", length = 60)
    private String logoutReason;
}
