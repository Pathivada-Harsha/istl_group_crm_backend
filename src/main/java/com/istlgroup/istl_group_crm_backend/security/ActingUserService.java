package com.istlgroup.istl_group_crm_backend.security;

import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.SessionExpiredException;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * The one place the server decides who is making a request.
 *
 * <p>Identity comes from the {@code USER_ID} attribute {@code LoginService} puts on the
 * {@link HttpSession} at login, and the role comes from the database row that id points
 * at. <b>No request header, query parameter or body field is consulted, ever.</b> The
 * browser sends {@code User-Id} / {@code User-Role} / {@code X-User-*} on most calls and
 * the server ignores all of them — they are vestigial.
 *
 * <p>Role is resolved live rather than snapshotted onto the session at login, so a
 * demotion or a role rename takes effect on the user's very next request instead of
 * lingering until they log out. The cost is one indexed primary-key lookup, memoised in a
 * request attribute so a handler taking both {@code @ActingUserId} and
 * {@code @ActingUserRole} still only queries once.
 *
 * <p>Every failure to resolve is a {@link SessionExpiredException} → 401. There is no
 * default user and no default role; see that class for why that matters.
 */
@Service
@RequiredArgsConstructor
public class ActingUserService {

    /** Session attribute written by {@code LoginService} — the numeric users.id. */
    public static final String SESSION_USER_ID = "USER_ID";

    /** Session attribute written by {@code LoginService} — the user's display name. */
    public static final String SESSION_USER_NAME = "USER_NAME";

    /** Request-scoped memo of the resolved row, so one request costs at most one query. */
    private static final String REQ_ATTR_USER = ActingUserService.class.getName() + ".USER";

    private final UsersRepo usersRepo;

    /**
     * The acting user's id.
     *
     * @throws SessionExpiredException when there is no session, or it carries no USER_ID
     */
    public Long requireUserId(HttpServletRequest request) {
        Long id = currentUserId(request);
        if (id == null) {
            throw new SessionExpiredException("Your session has expired. Please log in again.");
        }
        return id;
    }

    /**
     * The acting user's current role, from the database.
     *
     * @throws SessionExpiredException when identity cannot be established, or the session
     *         points at a user row that no longer exists
     */
    public String requireRole(HttpServletRequest request) {
        UsersEntity user = requireUser(request);
        String role = user.getRole();
        if (role == null || role.isBlank()) {
            // A user row with no role cannot be authorized against anything. Failing
            // closed is the only safe reading — the alternative is inventing a role.
            throw new SessionExpiredException("Your account has no role assigned. Please contact an administrator.");
        }
        return role;
    }

    /**
     * The acting user's display name, for attribution strings. Falls back to the session's
     * cached name and then to the login id, so this never returns blank for a real user.
     */
    public String requireName(HttpServletRequest request) {
        UsersEntity user = requireUser(request);
        if (user.getName() != null && !user.getName().isBlank()) return user.getName();

        HttpSession session = request.getSession(false);
        Object cached = session != null ? session.getAttribute(SESSION_USER_NAME) : null;
        if (cached != null && !String.valueOf(cached).isBlank()) return String.valueOf(cached);

        return user.getUser_id();
    }

    /** The acting user's row, loaded once per request. */
    public UsersEntity requireUser(HttpServletRequest request) {
        Object memo = request.getAttribute(REQ_ATTR_USER);
        if (memo instanceof UsersEntity cached) return cached;

        Long id = requireUserId(request);
        UsersEntity user = usersRepo.findById(id)
                .orElseThrow(() -> new SessionExpiredException(
                        "Your account could not be found. Please log in again."));

        request.setAttribute(REQ_ATTR_USER, user);
        return user;
    }

    /**
     * The acting user's id, or null when it cannot be established.
     *
     * <p>For the handful of call sites that genuinely need to ask rather than demand —
     * not a licence to fall back to a default identity.
     */
    public Long currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;

        Object raw = session.getAttribute(SESSION_USER_ID);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
