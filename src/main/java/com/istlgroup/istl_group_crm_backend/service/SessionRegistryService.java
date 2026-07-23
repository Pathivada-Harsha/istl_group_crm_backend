package com.istlgroup.istl_group_crm_backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.SessionLimitException;
import com.istlgroup.istl_group_crm_backend.dto.ActiveSessionDto;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.UserSessionEntity;
import com.istlgroup.istl_group_crm_backend.repo.LoginHistoryRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserSessionRepo;
import com.istlgroup.istl_group_crm_backend.util.UserAgentParser;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * SessionRegistryService — the heart of Active Login Management (Feature 2).
 *
 * Every successful login creates a row in user_sessions. A user may hold at
 * most {@code login-activity.max-active-sessions} (default 2) ACTIVE rows.
 * The check runs inside a transaction with a pessimistic row lock on the
 * user's ACTIVE sessions, so two simultaneous logins for the same user are
 * serialized by the database — no race condition can produce 3 live sessions.
 *
 * The limit applies to ALL roles including SUPERADMIN (confirmed requirement).
 */
@Service
@Slf4j
public class SessionRegistryService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    private UserSessionRepo sessionRepo;

    @Autowired
    private LoginHistoryRepo loginHistoryRepo;

    /** Real-time "you were signed out" push (WebSocket). Lazy to avoid startup cycles. */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private SessionEventPublisher sessionEventPublisher;

    @Value("${login-activity.max-active-sessions:2}")
    private int maxActiveSessions;

    @Value("${server.servlet.session.timeout}")
    private Duration sessionTimeout;

    /**
     * Per-request status cache so SessionFilter does not hit MySQL on every
     * single API call. Entries live for STATUS_CACHE_MS; eviction/termination
     * therefore takes effect within a few seconds on the evicted device.
     */
    private static final long STATUS_CACHE_MS = 5_000L;
    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();

    /** Throttle for last_seen_at writes — at most one UPDATE per session per minute. */
    private static final long TOUCH_INTERVAL_MS = 60_000L;
    private final Map<String, Long> lastTouch = new ConcurrentHashMap<>();

    private record CachedStatus(String status, long expiresAt) { }

    // ═════════════════════════════════════════════════════════════════════
    // LOGIN — register a session, enforcing the device limit
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Registers a new session for the user. Must be called AFTER credentials
     * and account status have been validated and AFTER the HTTP session has
     * been created.
     *
     * @throws SessionLimitException (HTTP 409) when the limit is reached and
     *         forceLogin was not requested — carries the active session list
     *         for the frontend warning dialog.
     */
    @Transactional
    public UserSessionEntity registerLogin(LoginEntity user,
                                           HttpServletRequest request,
                                           Map<String, String> clientContext,
                                           boolean forceLogin) {
        return registerLogin(user, request, clientContext, forceLogin, false);
    }

    @Transactional
    public UserSessionEntity registerLogin(LoginEntity user,
                                           HttpServletRequest request,
                                           Map<String, String> clientContext,
                                           boolean forceLogin,
                                           boolean logoutAllDevices) {

        LocalDateTime now = LocalDateTime.now(IST);

        // 🔒 Row-lock this user's ACTIVE sessions — serializes concurrent logins
        List<UserSessionEntity> active = sessionRepo.findActiveByUserIdForUpdate(user.getId());

        // Stale sessions (browser closed without logout) must not block a login
        LocalDateTime staleCutoff = now.minus(sessionTimeout);
        // Same-browser re-login: an ACTIVE row with the SAME device
        // fingerprint is this very browser logging in again (its old session
        // cookie was replaced or lost) — close it instead of counting it as
        // a second device. This is what makes one browser = one row, always.
        String incomingFingerprint = clientContext.get("deviceFingerprint");
        List<UserSessionEntity> live = new ArrayList<>();
        for (UserSessionEntity s : active) {
            if (s.getLastSeenAt() != null && s.getLastSeenAt().isBefore(staleCutoff)) {
                closeSessionRow(s, UserSessionEntity.STATUS_EXPIRED, "IDLE_TIMEOUT", now);
            } else if (incomingFingerprint != null && !incomingFingerprint.isBlank()
                    && incomingFingerprint.equals(s.getDeviceFingerprint())) {
                closeSessionRow(s, UserSessionEntity.STATUS_LOGGED_OUT, "RE_LOGIN", now);
                invalidateCache(s.getSessionHash());
            } else {
                live.add(s);
            }
        }

        if (live.size() >= maxActiveSessions) {
            if (!forceLogin && !logoutAllDevices) {
                List<ActiveSessionDto> dtos = live.stream().map(s -> toDto(s, null)).toList();
                throw new SessionLimitException(
                        "Your account is already active on " + maxActiveSessions + " devices.", dtos);
            }
            if (logoutAllDevices) {
                // User chose "Logout from all devices" → close EVERY live session
                for (UserSessionEntity s : live) {
                    closeSessionRow(s, UserSessionEntity.STATUS_EVICTED, "USER_LOGOUT_ALL_ON_LOGIN", now);
                    invalidateCache(s.getSessionHash());
                }
                log.info("Evicted ALL {} sessions for user {} (logout-all on new login)", live.size(), user.getId());
            } else {
                // User confirmed "Continue Login".
                // If the login dialog sent terminateSessionId, evict THAT
                // specific device (the user may pick any of their live
                // sessions, not just the oldest). Falls back to the oldest
                // when no valid selection was sent (old clients / safety).
                UserSessionEntity victim = null;
                Long selectedId = parseLong(clientContext.get("terminateSessionId"));
                if (selectedId != null) {
                    victim = live.stream()
                            .filter(x -> selectedId.equals(x.getId()))
                            .findFirst().orElse(null);
                }
                if (victim == null) {
                    victim = live.get(0); // query orders by loginAt ASC → oldest
                }
                closeSessionRow(victim, UserSessionEntity.STATUS_EVICTED, "NEW_DEVICE_LOGIN", now);
                invalidateCache(victim.getSessionHash());
                log.info("Evicted session {} for user {} (selected on new device login)",
                        victim.getId(), user.getId());
            }
        }

        // ── Create the new registry row ──────────────────────────────────
        UserSessionEntity s = new UserSessionEntity();
        s.setSessionHash(sha256(request.getSession(true).getId()));
        s.setUserId(user.getId());
        s.setEmployeeId(user.getUser_id());
        s.setUsername(user.getName());
        s.setEmail(user.getEmail());
        s.setIpAddress(clientIp(request));
        s.setLoginAt(now);
        s.setLastSeenAt(now);
        s.setStatus(UserSessionEntity.STATUS_ACTIVE);

        applyClientContext(s, request, clientContext);
        s = sessionRepo.save(s);

        // ── Login history row (SUCCESS) linked to the session ────────────
        LoginHistoryEntity h = new LoginHistoryEntity();
        h.setUserId(user.getId());
        h.setEmployeeId(user.getUser_id());
        h.setUsername(user.getName());
        h.setEmail(user.getEmail());
        h.setSessionRowId(s.getId());
        h.setLoginAt(now);
        h.setLoginStatus(LoginHistoryEntity.STATUS_SUCCESS);
        copyDeviceFields(s, h);
        loginHistoryRepo.save(h);

        return s;
    }

    /** Records a FAILED login attempt (invalid password, unknown user, inactive, locked). */
    @Transactional
    public void recordFailedLogin(String usernameEntered,
                                  LoginEntity userIfKnown,
                                  String status,
                                  String reason,
                                  HttpServletRequest request,
                                  Map<String, String> clientContext) {
        try {
            LoginHistoryEntity h = new LoginHistoryEntity();
            h.setUsernameEntered(usernameEntered);
            if (userIfKnown != null) {
                h.setUserId(userIfKnown.getId());
                h.setEmployeeId(userIfKnown.getUser_id());
                h.setUsername(userIfKnown.getName());
                h.setEmail(userIfKnown.getEmail());
            }
            h.setLoginAt(LocalDateTime.now(IST));
            h.setLoginStatus(status);
            h.setFailureReason(reason);
            h.setIpAddress(clientIp(request));

            UserSessionEntity tmp = new UserSessionEntity();
            applyClientContext(tmp, request, clientContext);
            copyDeviceFields(tmp, h);

            loginHistoryRepo.save(h);
        } catch (Exception e) {
            // Recording a failed attempt must never break the login response
            log.error("Could not record failed login for '{}'", usernameEntered, e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PER-REQUEST — status check + last-seen touch (called by SessionFilter)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Returns the registry status for a raw HTTP session id:
     * ACTIVE / EVICTED / ADMIN_TERMINATED / EXPIRED / LOGGED_OUT, or null when
     * the session predates this module (treated as ACTIVE by the caller).
     * Cached for a few seconds to avoid one DB hit per API call.
     */
    public String statusOf(String rawSessionId) {
        String hash = sha256(rawSessionId);
        long nowMs = System.currentTimeMillis();

        CachedStatus cached = statusCache.get(hash);
        if (cached != null && cached.expiresAt() > nowMs) {
            return cached.status();
        }

        String status = sessionRepo.findBySessionHash(hash)
                .map(UserSessionEntity::getStatus)
                .orElse(null);
        statusCache.put(hash, new CachedStatus(status, nowMs + STATUS_CACHE_MS));
        return status;
    }

    /** Updates last_seen_at, throttled to once per minute per session. */
    @Transactional
    public void touch(String rawSessionId) {
        String hash = sha256(rawSessionId);
        long nowMs = System.currentTimeMillis();
        Long last = lastTouch.get(hash);
        if (last != null && nowMs - last < TOUCH_INTERVAL_MS) return;
        lastTouch.put(hash, nowMs);
        try {
            sessionRepo.touch(hash, LocalDateTime.now(IST));
        } catch (Exception e) {
            log.debug("last_seen touch failed for session hash {}", hash, e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // LOGOUT / TERMINATE / SWEEP
    // ═════════════════════════════════════════════════════════════════════

    /** User-initiated logout — closes the registry row and its history row. */
    @Transactional
    public void closeByRawSessionId(String rawSessionId, String reason) {
        String hash = sha256(rawSessionId);
        sessionRepo.findBySessionHash(hash).ifPresent(s -> {
            if (UserSessionEntity.STATUS_ACTIVE.equals(s.getStatus())) {
                closeSessionRow(s, UserSessionEntity.STATUS_LOGGED_OUT, reason, LocalDateTime.now(IST));
            }
        });
        invalidateCache(hash);
    }

    /** Admin remote termination from the Active Sessions table. */
    @Transactional
    public UserSessionEntity terminate(Long sessionRowId) {
        UserSessionEntity s = sessionRepo.findById(sessionRowId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        if (UserSessionEntity.STATUS_ACTIVE.equals(s.getStatus())) {
            closeSessionRow(s, UserSessionEntity.STATUS_ADMIN_TERMINATED, "ADMIN_TERMINATED",
                    LocalDateTime.now(IST));
            invalidateCache(s.getSessionHash());
        }
        return s;
    }

    /** "Sign out all devices" for one user (user drawer action). */
    @Transactional
    public int terminateAllForUser(Long userId) {
        List<UserSessionEntity> live = sessionRepo.findActiveByUserIdForUpdate(userId);
        LocalDateTime now = LocalDateTime.now(IST);
        for (UserSessionEntity s : live) {
            closeSessionRow(s, UserSessionEntity.STATUS_ADMIN_TERMINATED, "SIGN_OUT_ALL", now);
            invalidateCache(s.getSessionHash());
        }
        return live.size();
    }

    // ── Self-service (Profile page "Active Sessions") ──────────────────────
    // Every user — not just admins — can see and manage their OWN devices.
    // These never touch other users' sessions; ownership is checked before
    // any write.

    /** A user's own active sessions, newest first — for the Profile page. */
    public List<UserSessionEntity> findActiveForUser(Long userId) {
        return sessionRepo.findActiveByUserId(userId);
    }

    /**
     * Signs the requesting user out of ONE of their own devices. Returns
     * false (no-op) if the session doesn't exist or belongs to someone else,
     * so a user can never terminate another account's session this way.
     */
    @Transactional
    public boolean terminateOwnSession(Long sessionRowId, Long requestingUserId) {
        UserSessionEntity s = sessionRepo.findById(sessionRowId).orElse(null);
        if (s == null || !s.getUserId().equals(requestingUserId)) return false;
        if (UserSessionEntity.STATUS_ACTIVE.equals(s.getStatus())) {
            closeSessionRow(s, UserSessionEntity.STATUS_ADMIN_TERMINATED, "USER_REMOTE_SIGNOUT", LocalDateTime.now(IST));
            invalidateCache(s.getSessionHash());
        }
        return true;
    }

    /**
     * "Sign out of all other devices" from the Profile page — closes every
     * ACTIVE session for this user EXCEPT the one making the request, so the
     * user's own browser stays logged in.
     */
    @Transactional
    public int terminateOthersForUser(Long userId, String currentRawSessionId) {
        String currentHash = currentRawSessionId != null ? sha256(currentRawSessionId) : null;
        List<UserSessionEntity> live = sessionRepo.findActiveByUserId(userId);
        LocalDateTime now = LocalDateTime.now(IST);
        int count = 0;
        for (UserSessionEntity s : live) {
            if (currentHash != null && currentHash.equals(s.getSessionHash())) continue;
            closeSessionRow(s, UserSessionEntity.STATUS_ADMIN_TERMINATED, "USER_REMOTE_SIGNOUT", now);
            invalidateCache(s.getSessionHash());
            count++;
        }
        return count;
    }

    /** Called by SessionSweeperJob — expires sessions idle past the timeout. */
    @Transactional
    public int sweepExpired() {
        LocalDateTime now = LocalDateTime.now(IST);
        List<UserSessionEntity> stale = sessionRepo.findStaleActive(now.minus(sessionTimeout));
        for (UserSessionEntity s : stale) {
            closeSessionRow(s, UserSessionEntity.STATUS_EXPIRED, "IDLE_TIMEOUT", now);
            invalidateCache(s.getSessionHash());
        }
        return stale.size();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    private void closeSessionRow(UserSessionEntity s, String status, String reason, LocalDateTime at) {
        s.setStatus(status);
        s.setLogoutAt(at);
        s.setLogoutReason(reason);
        sessionRepo.save(s);

        // 🔔 Real-time force-logout push to the terminated browser. Only for
        // terminations the affected browser did NOT initiate itself:
        //   EVICTED (login-page eviction), ADMIN_TERMINATED (admin/profile/
        //   login-activity remote sign-out), EXPIRED (idle timeout sweep).
        // Not for LOGGED_OUT (that browser pressed Logout / re-logged-in).
        if (UserSessionEntity.STATUS_EVICTED.equals(status)
                || UserSessionEntity.STATUS_ADMIN_TERMINATED.equals(status)
                || UserSessionEntity.STATUS_EXPIRED.equals(status)) {
            sessionEventPublisher.publishTerminated(s.getUserId(), s.getId(), status, reason);
        }

        loginHistoryRepo.findBySessionRowId(s.getId()).ifPresent(h -> {
            h.setLogoutAt(at);
            if (h.getLoginAt() != null) {
                h.setSessionDurationSec(Duration.between(h.getLoginAt(), at).getSeconds());
            }
            loginHistoryRepo.save(h);
        });
    }

    private void applyClientContext(UserSessionEntity s, HttpServletRequest request,
                                    Map<String, String> ctx) {
        // Server-parsed User-Agent is the source of truth
        UserAgentParser.ParsedUa ua = UserAgentParser.parse(request.getHeader("User-Agent"));
        s.setBrowser(ua.browser);
        s.setBrowserVersion(ua.browserVersion);
        s.setOperatingSystem(ua.operatingSystem);
        s.setDeviceType(ua.deviceType);
        s.setDeviceName(ua.deviceName);

        if (ctx == null) return;

        // Client hints refine what the server cannot know
        String clientDeviceType = ctx.get("deviceType");
        if ("LAPTOP".equalsIgnoreCase(clientDeviceType) && "DESKTOP".equals(s.getDeviceType())) {
            s.setDeviceType("LAPTOP"); // battery API detected on the client
        }
        s.setScreenResolution(trim(ctx.get("screenResolution"), 20));
        s.setTimeZone(trim(ctx.get("timeZone"), 60));
        s.setCountry(trim(ctx.get("country"), 80));
        s.setState(trim(ctx.get("state"), 80));
        s.setCity(trim(ctx.get("city"), 80));
        s.setLatitude(parseDouble(ctx.get("latitude")));
        s.setLongitude(parseDouble(ctx.get("longitude")));
        s.setDeviceFingerprint(trim(ctx.get("deviceFingerprint"), 64));

        // Timezone-based country fallback when location permission was denied
        if (s.getCountry() == null && "Asia/Kolkata".equals(s.getTimeZone())) {
            s.setCountry("India");
        }
    }

    private void copyDeviceFields(UserSessionEntity s, LoginHistoryEntity h) {
        h.setIpAddress(h.getIpAddress() != null ? h.getIpAddress() : s.getIpAddress());
        h.setBrowser(s.getBrowser());
        h.setBrowserVersion(s.getBrowserVersion());
        h.setOperatingSystem(s.getOperatingSystem());
        h.setDeviceType(s.getDeviceType());
        h.setDeviceName(s.getDeviceName());
        h.setScreenResolution(s.getScreenResolution());
        h.setTimeZone(s.getTimeZone());
        h.setCountry(s.getCountry());
        h.setState(s.getState());
        h.setCity(s.getCity());
        h.setLatitude(s.getLatitude());
        h.setLongitude(s.getLongitude());
    }

    public ActiveSessionDto toDto(UserSessionEntity s, String currentRawSessionId) {
        ActiveSessionDto d = new ActiveSessionDto();
        d.setId(s.getId());
        d.setUserId(s.getUserId());
        d.setEmployeeId(s.getEmployeeId());
        d.setUsername(s.getUsername());
        d.setEmail(s.getEmail());
        d.setIpAddress(s.getIpAddress());
        d.setBrowser(s.getBrowser());
        d.setBrowserVersion(s.getBrowserVersion());
        d.setOperatingSystem(s.getOperatingSystem());
        d.setDeviceType(s.getDeviceType());
        d.setDeviceName(s.getDeviceName());
        d.setCity(s.getCity());
        d.setState(s.getState());
        d.setCountry(s.getCountry());
        d.setLoginAt(s.getLoginAt());
        d.setLastSeenAt(s.getLastSeenAt());
        d.setStatus(s.getStatus());
        if (currentRawSessionId != null) {
            d.setCurrentSession(sha256(currentRawSessionId).equals(s.getSessionHash()));
        }
        return d;
    }

    private void invalidateCache(String hash) {
        statusCache.remove(hash);
        lastTouch.remove(hash);
    }

    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank())
                ? xff.split(",")[0].trim()
                : request.getRemoteAddr();
        return normalizeIp(ip);
    }

    /** Makes localhost/IPv6 addresses human-readable in the monitor. */
    private static String normalizeIp(String ip) {
        if (ip == null) return null;
        // IPv6 loopback → shown as the familiar localhost address
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        // IPv4-mapped IPv6 (e.g. ::ffff:192.168.1.5) → plain IPv4
        if (ip.startsWith("::ffff:") && ip.indexOf('.') > 0) return ip.substring(7);
        return ip;
    }

    /**
     * Late location fill (LOGIN ACTIVITY): the browser permission prompt can
     * still be open when login completes, so the frontend reports coordinates
     * in the background once the user grants access. Updates the live session
     * row and the matching login_history record.
     */
    @Transactional
    public void updateGeo(String rawSessionId, Long userId, Double lat, Double lng,
                          String city, String state, String country) {
        if (rawSessionId == null || userId == null || lat == null || lng == null) return;
        String hash = sha256(rawSessionId);
        sessionRepo.updateGeo(hash, lat, lng, city, state, country);
        loginHistoryRepo.fillMissingGeoForLatestLogin(userId, lat, lng, city, state, country);
        statusCache.remove(hash); // next statusOf() sees fresh data
    }

    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String trim(String v, int max) {
        if (v == null || v.isBlank()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static Long parseLong(String v) {
        try {
            return v == null || v.isBlank() ? null : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String v) {
        try {
            return v == null || v.isBlank() ? null : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}