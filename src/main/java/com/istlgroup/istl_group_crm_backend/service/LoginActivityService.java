package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.dto.ActiveSessionDto;
import com.istlgroup.istl_group_crm_backend.dto.DashboardStatsDto;
import com.istlgroup.istl_group_crm_backend.dto.UserActivitySummaryDto;
import com.istlgroup.istl_group_crm_backend.entity.AuditLogArchiveEntity;
import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryArchiveEntity;
import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryBase;
import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryEntity;
import com.istlgroup.istl_group_crm_backend.repo.AuditLogArchiveRepo;
import com.istlgroup.istl_group_crm_backend.repo.AuditLogRepo;
import com.istlgroup.istl_group_crm_backend.repo.LoginHistoryArchiveRepo;
import com.istlgroup.istl_group_crm_backend.repo.LoginHistoryRepo;
import com.istlgroup.istl_group_crm_backend.repo.LoginRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserSessionRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PagedResponseWrapper;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * LoginActivityService — read side of the Login & Activity Monitor page.
 * All queries are paginated; dashboard cards use targeted COUNT queries on
 * indexed columns. Archive queries run only when the Archived tab is opened
 * (archive=true) and are restricted to ADMIN/SUPERADMIN by the controller.
 */
@Service
public class LoginActivityService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private LoginHistoryRepo loginHistoryRepo;
    @Autowired private LoginHistoryArchiveRepo loginHistoryArchiveRepo;
    @Autowired private AuditLogRepo auditLogRepo;
    @Autowired private AuditLogArchiveRepo auditLogArchiveRepo;
    @Autowired private UserSessionRepo sessionRepo;
    @Autowired private LoginRepo loginRepo;
    @Autowired private SessionRegistryService sessionRegistryService;

    // ═════════════════════════════════════════════════════════════════════
    // Dashboard cards + charts
    // ═════════════════════════════════════════════════════════════════════

    public DashboardStatsDto dashboardStats() {
        LocalDateTime dayStart = LocalDate.now(IST).atStartOfDay();
        LocalDateTime dayEnd   = dayStart.plusDays(1);
        LocalDateTime weekAgo  = dayStart.minusDays(6);

        DashboardStatsDto dto = new DashboardStatsDto();
        dto.setTotalLoginsToday(loginHistoryRepo.countSuccessBetween(dayStart, dayEnd));
        dto.setFailedLoginsToday(loginHistoryRepo.countFailedBetween(dayStart, dayEnd));
        dto.setActiveSessions(sessionRepo.countActive());
        dto.setUniqueDevicesToday(loginHistoryRepo.countUniqueDevicesBetween(dayStart, dayEnd));
        dto.setUniqueLocationsToday(loginHistoryRepo.countUniqueCitiesBetween(dayStart, dayEnd));
        dto.setTotalActivitiesToday(auditLogRepo.countBetween(dayStart, dayEnd));

        List<Map<String, Object>> topUsers = new ArrayList<>();
        for (Object[] row : auditLogRepo.mostActiveUsersBetween(dayStart, dayEnd, PageRequest.of(0, 5))) {
            Map<String, Object> m = new HashMap<>();
            m.put("userId", row[0]);
            m.put("username", row[1]);
            m.put("count", row[2]);
            topUsers.add(m);
        }
        dto.setMostActiveUsers(topUsers);

        // Login trend — last 7 days, zero-filled so the chart never has holes
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            byDay.put(LocalDate.now(IST).minusDays(6 - i).toString(), 0L);
        }
        for (Object[] row : loginHistoryRepo.loginTrendSince(weekAgo)) {
            byDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        byDay.forEach((date, count) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("date", date);
            m.put("count", count);
            trend.add(m);
        });
        dto.setLoginTrend(trend);

        List<Map<String, Object>> deviceSplit = new ArrayList<>();
        for (Object[] row : loginHistoryRepo.deviceSplitSince(weekAgo)) {
            Map<String, Object> m = new HashMap<>();
            m.put("deviceType", row[0]);
            m.put("count", ((Number) row[1]).longValue());
            deviceSplit.add(m);
        }
        dto.setDeviceSplit(deviceSplit);

        return dto;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Login history — hot table and archive share one filter builder
    // ═════════════════════════════════════════════════════════════════════

    public PagedResponseWrapper<?> loginHistory(Long userId, LocalDateTime from, LocalDateTime to,
                                                String status, String deviceType, String browser,
                                                String os, String city, String search,
                                                int page, int size, String sortBy, String sortDir,
                                                boolean archive) {
        Sort sort = buildSort(sortBy, sortDir, "loginAt");
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), clampSize(size), sort);

        if (archive) {
            Specification<LoginHistoryArchiveEntity> spec = (root, q, cb) ->
                    loginHistoryPredicate(root, cb, userId, from, to, status, deviceType, browser, os, city, search);
            Page<LoginHistoryArchiveEntity> result = loginHistoryArchiveRepo.findAll(spec, pageable);
            return PagedResponseWrapper.of(result.getContent(), page, size, result.getTotalElements());
        }

        Specification<LoginHistoryEntity> spec = (root, q, cb) ->
                loginHistoryPredicate(root, cb, userId, from, to, status, deviceType, browser, os, city, search);
        Page<LoginHistoryEntity> result = loginHistoryRepo.findAll(spec, pageable);
        return PagedResponseWrapper.of(result.getContent(), page, size, result.getTotalElements());
    }

    /** Works for both hot and archive entities — identical field names via base class. */
    private Predicate loginHistoryPredicate(Root<?> root, CriteriaBuilder cb,
                                            Long userId, LocalDateTime from, LocalDateTime to,
                                            String status, String deviceType, String browser,
                                            String os, String city, String search) {
        List<Predicate> ps = new ArrayList<>();
        if (userId != null)          ps.add(cb.equal(root.get("userId"), userId));
        if (from != null)            ps.add(cb.greaterThanOrEqualTo(root.get("loginAt"), from));
        if (to != null)              ps.add(cb.lessThan(root.get("loginAt"), to));
        if (notBlank(deviceType))    ps.add(cb.equal(root.get("deviceType"), deviceType));
        if (notBlank(browser))       ps.add(cb.equal(root.get("browser"), browser));
        if (notBlank(os))            ps.add(cb.equal(root.get("operatingSystem"), os));
        if (notBlank(city))          ps.add(cb.equal(root.get("city"), city));
        if (notBlank(status)) {
            if ("FAILED".equalsIgnoreCase(status)) {
                ps.add(cb.notEqual(root.get("loginStatus"), "SUCCESS"));
            } else {
                ps.add(cb.equal(root.get("loginStatus"), status));
            }
        }
        if (notBlank(search)) {
            String like = "%" + search.trim().toLowerCase() + "%";
            ps.add(cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("username"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("employeeId"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("ipAddress"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("deviceName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("city"), "")), like)
            ));
        }
        return cb.and(ps.toArray(new Predicate[0]));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Activity / audit log — same pattern
    // ═════════════════════════════════════════════════════════════════════

    public PagedResponseWrapper<?> activities(Long userId, LocalDateTime from, LocalDateTime to,
                                              String module, String operation, String status,
                                              String search, int page, int size,
                                              String sortBy, String sortDir, boolean archive) {
        Sort sort = buildSort(sortBy, sortDir, "createdAt");
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), clampSize(size), sort);

        if (archive) {
            Specification<AuditLogArchiveEntity> spec = (root, q, cb) ->
                    auditPredicate(root, cb, userId, from, to, module, operation, status, search);
            Page<AuditLogArchiveEntity> result = auditLogArchiveRepo.findAll(spec, pageable);
            return PagedResponseWrapper.of(result.getContent(), page, size, result.getTotalElements());
        }

        Specification<AuditLogEntity> spec = (root, q, cb) ->
                auditPredicate(root, cb, userId, from, to, module, operation, status, search);
        Page<AuditLogEntity> result = auditLogRepo.findAll(spec, pageable);
        return PagedResponseWrapper.of(result.getContent(), page, size, result.getTotalElements());
    }

    private Predicate auditPredicate(Root<?> root, CriteriaBuilder cb,
                                     Long userId, LocalDateTime from, LocalDateTime to,
                                     String module, String operation, String status, String search) {
        List<Predicate> ps = new ArrayList<>();
        if (userId != null)       ps.add(cb.equal(root.get("userId"), userId));
        if (from != null)         ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        if (to != null)           ps.add(cb.lessThan(root.get("createdAt"), to));
        if (notBlank(module))     ps.add(cb.equal(root.get("module"), module));
        if (notBlank(operation))  ps.add(cb.equal(root.get("operation"), operation));
        if (notBlank(status))     ps.add(cb.equal(root.get("status"), status));
        if (notBlank(search)) {
            String like = "%" + search.trim().toLowerCase() + "%";
            ps.add(cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("username"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("page"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("ipAddress"), "")), like)
            ));
        }
        return cb.and(ps.toArray(new Predicate[0]));
    }

    public Object activityById(Long id, boolean archive) throws CustomException {
        if (archive) {
            return auditLogArchiveRepo.findById(id)
                    .orElseThrow(() -> new CustomException("Activity not found"));
        }
        return auditLogRepo.findById(id)
                .orElseThrow(() -> new CustomException("Activity not found"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Active sessions
    // ═════════════════════════════════════════════════════════════════════

    public List<ActiveSessionDto> activeSessions(String currentRawSessionId) {
        return sessionRepo.findAllActive().stream()
                .map(s -> sessionRegistryService.toDto(s, currentRawSessionId))
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    // User details drawer (Feature 5)
    // ═════════════════════════════════════════════════════════════════════

    public UserActivitySummaryDto userSummary(Long userId, String currentRawSessionId)
            throws CustomException {

        LoginEntity user = loginRepo.findById(userId)
                .orElseThrow(() -> new CustomException("Invalid User"));

        UserActivitySummaryDto dto = new UserActivitySummaryDto();
        dto.setUserId(user.getId());
        dto.setEmployeeId(user.getUser_id());
        dto.setName(user.getName());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setRole(user.getRole());
        dto.setDesignation(user.getDesignation());
        dto.setTeam(user.getTeam());
        dto.setIsActive(user.getIs_active());

        dto.setTotalLoginCount(loginHistoryRepo.countLoginsByUser(userId));
        Double avg = loginHistoryRepo.avgSessionDurationByUser(userId);
        dto.setAvgSessionDurationSec(avg == null ? null : Math.round(avg));
        // Latest device / browser / location — taken from the most recent
        // successful login (requested UX: "Latest", not most-frequently-used)
        loginHistoryRepo.findFirstByUserIdAndLoginStatusOrderByLoginAtDesc(
                userId, LoginHistoryBase.STATUS_SUCCESS).ifPresent(latest -> {
            dto.setMostUsedDevice(latest.getDeviceType());
            dto.setMostUsedBrowser(latest.getBrowser());
            dto.setMostUsedLocation(latest.getCity());
        });

        dto.setActiveSessions(sessionRepo.findActiveByUserId(userId).stream()
                .map(s -> sessionRegistryService.toDto(s, currentRawSessionId))
                .toList());

        Specification<LoginHistoryEntity> lhSpec = (root, q, cb) ->
                cb.equal(root.get("userId"), userId);
        dto.setRecentLogins(loginHistoryRepo.findAll(lhSpec,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "loginAt"))).getContent());

        dto.setRecentActivities(auditLogRepo.recentByUser(userId, PageRequest.of(0, 10)));

        return dto;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Filter dropdown values (distinct browsers / OS / cities on the page)
    // ═════════════════════════════════════════════════════════════════════

    public Map<String, Object> filterOptions() {
        Map<String, Object> m = new HashMap<>();
        m.put("deviceTypes", List.of("MOBILE", "LAPTOP", "DESKTOP", "TABLET"));
        m.put("statuses", List.of("SUCCESS", "FAILED", "FAILED_INVALID_PASSWORD",
                "FAILED_USER_NOT_FOUND", "FAILED_ACCOUNT_INACTIVE", "FAILED_ACCOUNT_LOCKED"));
        m.put("operations", List.of("LOGIN", "LOGOUT", "SESSION_TIMEOUT", "VIEW", "CREATE",
                "UPDATE", "DELETE", "APPROVE", "REJECT", "ASSIGN", "REASSIGN", "STATUS_CHANGE",
                "IMPORT", "EXPORT", "DOWNLOAD", "UPLOAD", "SEARCH", "PASSWORD_CHANGE",
                "PROFILE_UPDATE", "ROLE_CHANGE", "PERMISSION_CHANGE", "API_FAILURE",
                "VALIDATION_ERROR", "SECURITY_EVENT"));
        m.put("modules", List.of("AUTH", "SECURITY", "DASHBOARD", "LEADS", "CUSTOMERS",
                "PROPOSALS", "QUOTATIONS", "INVOICES", "BILLS", "ORDER_BOOK", "INVENTORY",
                "REPORTS", "USERS", "ROLES", "PERMISSIONS", "OFFICE_USE", "PROFILE", "NAVIGATION"));
        return m;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    private static final List<String> SORTABLE = List.of(
            "loginAt", "logoutAt", "sessionDurationSec", "loginStatus", "username",
            "deviceType", "browser", "city", "createdAt", "module", "operation", "status");

    private Sort buildSort(String sortBy, String sortDir, String defaultField) {
        String field = (sortBy != null && SORTABLE.contains(sortBy)) ? sortBy : defaultField;
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 5000); // 5000 = export ceiling
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank() && !"ALL".equalsIgnoreCase(v);
    }

}