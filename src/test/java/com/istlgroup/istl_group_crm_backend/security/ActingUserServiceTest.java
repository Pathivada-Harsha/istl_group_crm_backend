package com.istlgroup.istl_group_crm_backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import com.istlgroup.istl_group_crm_backend.customException.SessionExpiredException;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

/**
 * The trust boundary, one test per acceptance criterion.
 *
 * <p>Every test here makes the same point: nothing the client sends may change who the
 * server thinks is calling. So each one sends hostile headers alongside a real session and
 * asserts the session wins — that IS the property under test, which is why the headers
 * appear in tests that would otherwise have no need for them.
 */
@ExtendWith(MockitoExtension.class)
class ActingUserServiceTest {

    private static final long SESSION_USER = 42L;
    private static final long OTHER_USER   = 7L;

    @Mock private UsersRepo usersRepo;

    @InjectMocks private ActingUserService service;

    private UsersEntity telecaller;

    @BeforeEach
    void setUp() {
        telecaller = new UsersEntity();
        telecaller.setId(SESSION_USER);
        telecaller.setName("Real Telecaller");
        telecaller.setUser_id("EMP042");
        telecaller.setRole("TELECALLER");
        telecaller.setIs_active(1L);

        lenient().when(usersRepo.findById(SESSION_USER)).thenReturn(Optional.of(telecaller));
    }

    /** A request carrying a live session for {@link #SESSION_USER}. */
    private MockHttpServletRequest authenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ActingUserService.SESSION_USER_ID, SESSION_USER);
        session.setAttribute(ActingUserService.SESSION_USER_NAME, "Real Telecaller");
        request.setSession(session);
        return request;
    }

    /** Every identity header an attacker could reach for, all pointing somewhere else. */
    private MockHttpServletRequest spoofed() {
        MockHttpServletRequest request = authenticated();
        request.addHeader("User-Id",     String.valueOf(OTHER_USER));
        request.addHeader("X-User-Id",   String.valueOf(OTHER_USER));
        request.addHeader("x-user-id",   String.valueOf(OTHER_USER));
        request.addHeader("User-Role",   "SUPERADMIN");
        request.addHeader("X-User-Role", "SUPERADMIN");
        request.addHeader("x-user-role", "SUPERADMIN");
        request.addHeader("X-User-Name", "Someone Else");
        return request;
    }

    // ── R1: identity comes from the session ───────────────────────────────────

    @Test
    void idComesFromTheSession() {
        assertEquals(SESSION_USER, service.requireUserId(authenticated()));
    }

    @Test
    void spoofedIdHeadersAreIgnored() {
        assertEquals(SESSION_USER, service.requireUserId(spoofed()),
            "the User-Id header must not be able to change who the caller is");
        verify(usersRepo, never()).findById(OTHER_USER);
    }

    @Test
    void spoofedRoleHeadersAreIgnored() {
        assertEquals("TELECALLER", service.requireRole(spoofed()),
            "sending User-Role: SUPERADMIN must not promote the caller");
    }

    @Test
    void spoofedNameHeaderIsIgnored() {
        assertEquals("Real Telecaller", service.requireName(spoofed()),
            "a caller must not be able to stamp somebody else's name on their writes");
    }

    @Test
    void roleIsReadFromTheDatabaseNotSnapshottedAtLogin() {
        assertEquals("TELECALLER", service.requireRole(authenticated()));

        // A demotion applied after login takes effect on the caller's very next request.
        telecaller.setRole("BD_EXECUTIVE");
        assertEquals("BD_EXECUTIVE", service.requireRole(authenticated()));
    }

    // ── R3: no fallback identity, ever ────────────────────────────────────────

    @Test
    void noSessionIsUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Id", "1");             // the old hardcoded fallback
        request.addHeader("User-Role", "SUPERADMIN");
        assertThrows(SessionExpiredException.class, () -> service.requireUserId(request));
        assertThrows(SessionExpiredException.class, () -> service.requireRole(request));
    }

    @Test
    void sessionWithoutUserIdIsUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());     // a session, but no USER_ID on it
        assertThrows(SessionExpiredException.class, () -> service.requireUserId(request));
    }

    @Test
    void sessionPointingAtAMissingUserIsUnauthenticated() {
        when(usersRepo.findById(SESSION_USER)).thenReturn(Optional.empty());
        assertThrows(SessionExpiredException.class, () -> service.requireRole(authenticated()));
    }

    @Test
    void userWithNoRoleFailsClosed() {
        telecaller.setRole(null);
        assertThrows(SessionExpiredException.class, () -> service.requireRole(authenticated()),
            "a role-less account must not be handed an invented role");
    }

    // ── The user row is loaded at most once per request ───────────────────────

    @Test
    void userRowIsLoadedOncePerRequest() {
        MockHttpServletRequest request = authenticated();
        service.requireRole(request);
        service.requireName(request);
        service.requireUser(request);
        verify(usersRepo, times(1)).findById(anyLong());
    }

    @Test
    void aSeparateRequestLoadsAgain() {
        service.requireRole(authenticated());
        service.requireRole(authenticated());
        verify(usersRepo, times(2)).findById(anyLong());
    }

    // ── USER_ID stored as something other than a Long ─────────────────────────

    @Test
    void aStringUserIdOnTheSessionStillResolves() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ActingUserService.SESSION_USER_ID, " 42 ");
        request.setSession(session);
        assertEquals(SESSION_USER, service.requireUserId(request));
    }

    @Test
    void anUnparseableUserIdOnTheSessionIsUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ActingUserService.SESSION_USER_ID, "not-a-number");
        request.setSession(session);
        assertThrows(SessionExpiredException.class, () -> service.requireUserId(request));
    }
}
