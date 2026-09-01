package com.istlgroup.istl_group_crm_backend.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.istlgroup.istl_group_crm_backend.config.GlobalExceptionHandler;
import com.istlgroup.istl_group_crm_backend.controller.LeadsController;
import com.istlgroup.istl_group_crm_backend.controller.RoleHierarchyController;
import com.istlgroup.istl_group_crm_backend.entity.RoleHierarchyEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.service.LeadsService;
import com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService;

/**
 * The two escalation paths this change exists to close, driven through the real MVC
 * stack: the argument resolver, the controller, and the exception handler that turns an
 * unresolvable identity into 401.
 *
 * <p>These are the security spec's acceptance criteria written as tests. They are
 * deliberately end-to-end-ish rather than unit tests of the resolver, because the property
 * only holds if the resolver is actually the thing that binds the parameter — a unit test
 * of {@link ActingUserService} alone would still pass if a controller quietly went back to
 * reading a header.
 */
@ExtendWith(MockitoExtension.class)
class ActingIdentityEndToEndTest {

    private static final long TELECALLER_ID = 42L;

    @Mock private UsersRepo usersRepo;
    @Mock private RoleHierarchyService hierarchyService;

    @InjectMocks private ActingUserService actingUserService;

    private MockMvc mvc;
    private MockHttpSession telecallerSession;

    @BeforeEach
    void setUp() {
        UsersEntity telecaller = new UsersEntity();
        telecaller.setId(TELECALLER_ID);
        telecaller.setName("Real Telecaller");
        telecaller.setUser_id("EMP042");
        telecaller.setRole("TELECALLER");
        telecaller.setIs_active(1L);
        lenient().when(usersRepo.findById(TELECALLER_ID)).thenReturn(Optional.of(telecaller));

        RoleHierarchyController controller = new RoleHierarchyController();
        ReflectionTestUtils.setField(controller, "hierarchyService", hierarchyService);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new ActingUserArgumentResolver(actingUserService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        telecallerSession = new MockHttpSession();
        telecallerSession.setAttribute(ActingUserService.SESSION_USER_ID, TELECALLER_ID);
    }

    private static final String BODY = """
            {"roleName":"TELECALLER","levelOrder":1,"description":"pwned",
             "canAssignRoles":"[\\"SUPERADMIN\\"]","canSeeRoles":"[\\"SUPERADMIN\\"]"}
            """;

    // ── R4: an authenticated non-admin claiming SUPERADMIN is still refused ────

    @Test
    void nonAdminClaimingSuperadminCannotSaveRoleHierarchy() throws Exception {
        mvc.perform(post("/role-hierarchy/save")
                        .session(telecallerSession)
                        .header("User-Role", "SUPERADMIN")
                        .header("X-User-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content(BODY))
           .andExpect(status().isForbidden());

        verify(hierarchyService, never()).saveHierarchy(any());
    }

    @Test
    void nonAdminClaimingSuperadminCannotUpdateRoleHierarchy() throws Exception {
        mvc.perform(post("/role-hierarchy/save")
                        .session(telecallerSession)
                        .header("User-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content(BODY))
           .andExpect(status().isForbidden());

        verify(hierarchyService, never()).saveHierarchy(any(RoleHierarchyEntity.class));
    }

    @Test
    void nonAdminClaimingSuperadminCannotDeleteRoleHierarchy() throws Exception {
        mvc.perform(delete("/role-hierarchy/TELECALLER")
                        .session(telecallerSession)
                        .header("User-Role", "SUPERADMIN"))
           .andExpect(status().isForbidden());

        verify(hierarchyService, never()).deleteHierarchy("TELECALLER");
    }

    // ── R3: no session → 401, never a default identity ────────────────────────

    @Test
    void noSessionIsUnauthorizedNotServedAsADefaultUser() throws Exception {
        mvc.perform(post("/role-hierarchy/save")
                        .header("User-Id", "1")             // the old hardcoded fallback
                        .header("User-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content(BODY))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.error").value("SESSION_EXPIRED"));

        verify(hierarchyService, never()).saveHierarchy(any());
    }

    // ── R5: the lead assign/close guard is fed the REAL caller ────────────────

    @Test
    void spoofingUserIdDoesNotChangeWhoTheLeadGuardSees() throws Exception {
        // The subtree guard in LeadsService is correct; the hole was that LeadsController
        // handed it whatever id the browser put in the User-Id header. Assert that the
        // service is told the SESSION user, so a caller cannot inherit an admin subtree.
        LeadsService leadsService = org.mockito.Mockito.mock(LeadsService.class);
        LeadsController leads = new LeadsController();
        ReflectionTestUtils.setField(leads, "leadsService", leadsService);

        MockMvc leadsMvc = MockMvcBuilders.standaloneSetup(leads)
                .setCustomArgumentResolvers(new ActingUserArgumentResolver(actingUserService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        leadsMvc.perform(put("/leads/update/99")
                        .session(telecallerSession)
                        .header("User-Id", "1")            // claim to be the admin
                        .header("User-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"assignedTo\":7}"))
               .andExpect(status().isOk());

        verify(leadsService).updateLead(
                org.mockito.ArgumentMatchers.eq(99L),
                any(),
                org.mockito.ArgumentMatchers.eq(TELECALLER_ID),   // NOT 1
                org.mockito.ArgumentMatchers.eq("TELECALLER"));   // NOT SUPERADMIN
    }

    // ── The genuine admin still gets through ──────────────────────────────────

    @Test
    void aRealSuperadminIsStillAllowed() throws Exception {
        UsersEntity admin = new UsersEntity();
        admin.setId(1L);
        admin.setName("Super Admin");
        admin.setRole("SUPERADMIN");
        admin.setIs_active(1L);
        lenient().when(usersRepo.findById(1L)).thenReturn(Optional.of(admin));

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute(ActingUserService.SESSION_USER_ID, 1L);

        mvc.perform(delete("/role-hierarchy/TELECALLER").session(adminSession))
           .andExpect(status().isOk());

        verify(hierarchyService).deleteHierarchy("TELECALLER");
    }
}
