package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

/**
 * The canonical "users I can act on" rule, one test per acceptance criterion of the
 * reporting-hierarchy change.
 *
 * <p>No Spring context and no database: the service's only collaborators are the users
 * repository and the role hierarchy lookup. The fixture is deliberately the shape the
 * spec was written against — a director over a procurement manager who has eight reports
 * spanning several roles — so "no role allow-list can express this" is baked into the
 * data rather than asserted in prose.
 *
 * <pre>
 *   SUPERADMIN (1)                    top level, no manager
 *   DIRECTOR   (10)                   level 3
 *     └── PROCUREMENT_MANAGER (20)    level 3, eight reports across five roles
 *           ├── 201..208
 *     SALES_MANAGER (30)              level 3, a sibling branch — must never leak in
 *           └── 301
 *   TELECALLER (40)                   level 5, no manager, no reports
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class UserScopeServiceTest {

    @Mock private UsersRepo usersRepo;
    @Mock private RoleHierarchyService roleHierarchyService;

    @InjectMocks private UserScopeService service;

    private final Map<Long, UsersEntity> world = new LinkedHashMap<>();

    private UsersEntity user(long id, String name, String role, Long managerId) {
        UsersEntity u = new UsersEntity();
        u.setId(id);
        u.setName(name);
        u.setRole(role);
        u.setManagerId(managerId);
        u.setIs_active(1L);
        return u;
    }

    @BeforeEach
    void setUp() {
        world.clear();
        add(user(1L,  "Super",      "SUPERADMIN",           null));
        add(user(10L, "Director",   "DIRECTOR",             null));
        add(user(20L, "Proc Mgr",   "PROCUREMENT_MANAGER",  10L));
        // Eight reports under the procurement manager, deliberately across five roles.
        add(user(201L, "P1", "PROCUREMENT_EXECUTIVE", 20L));
        add(user(202L, "P2", "PROCUREMENT_EXECUTIVE", 20L));
        add(user(203L, "P3", "STORE_KEEPER",          20L));
        add(user(204L, "P4", "STORE_KEEPER",          20L));
        add(user(205L, "P5", "SITE_ENGINEER",         20L));
        add(user(206L, "P6", "ACCOUNTS_EXECUTIVE",    20L));
        add(user(207L, "P7", "BD_EXECUTIVE",          20L));
        add(user(208L, "P8", "TELECALLER",            20L));
        // A sibling branch under the same director.
        add(user(30L,  "Sales Mgr", "SALES_MANAGER", 10L));
        add(user(301L, "S1",        "BD_EXECUTIVE",  30L));
        // A leaf with no manager and no reports.
        add(user(40L,  "Telecaller", "TELECALLER", null));

        lenient().when(usersRepo.findById(org.mockito.ArgumentMatchers.anyLong()))
                 .thenAnswer(inv -> Optional.ofNullable(world.get(inv.getArgument(0, Long.class))));
        lenient().when(usersRepo.findActiveReportingEdges()).thenReturn(edges());
        lenient().when(usersRepo.findAllActiveUsers()).thenReturn(new ArrayList<>(world.values()));
        lenient().when(usersRepo.findAllById(org.mockito.ArgumentMatchers.any()))
                 .thenAnswer(inv -> {
                     List<UsersEntity> out = new ArrayList<>();
                     for (Long id : (Iterable<Long>) inv.getArgument(0)) {
                         UsersEntity u = world.get(id);
                         if (u != null) out.add(u);
                     }
                     return out;
                 });
        // Only SUPERADMIN is top level in this fixture; everyone else is level 3+.
        lenient().when(roleHierarchyService.isTopLevelRole(org.mockito.ArgumentMatchers.anyString()))
                 .thenAnswer(inv -> "SUPERADMIN".equals(inv.getArgument(0)));
    }

    private void add(UsersEntity u) { world.put(u.getId(), u); }

    /** The [id, manager_id] rows the repository hands back for active users. */
    private List<Object[]> edges() {
        List<Object[]> rows = new ArrayList<>();
        for (UsersEntity u : world.values()) rows.add(new Object[] { u.getId(), u.getManagerId() });
        return rows;
    }

    // ── C1 ────────────────────────────────────────────────────────────────────

    @Test
    void topLevelUserSeesTheWholeOrganisation() {
        Set<Long> ids = service.getActionableUserIds(1L);
        assertEquals(world.keySet(), ids,
            "a hierarchy level 1-2 role is not subtree-scoped");
    }

    @Test
    void managerSeesExactlyTheirOwnReportsPlusThemselves() {
        Set<Long> ids = service.getActionableUserIds(20L);
        assertEquals(Set.copyOf(Arrays.asList(20L, 201L, 202L, 203L, 204L, 205L, 206L, 207L, 208L)), ids,
            "the procurement manager must see his eight reports and himself, nobody else");
    }

    @Test
    void managerDoesNotSeeASiblingBranch() {
        Set<Long> ids = service.getActionableUserIds(20L);
        assertFalse(ids.contains(30L),  "a peer manager is not a report");
        assertFalse(ids.contains(301L), "a peer manager's report is not a report");
        assertFalse(ids.contains(10L),  "your own manager is above you, not beneath you");
    }

    @Test
    void reportsAreTransitiveNotJustDirect() {
        Set<Long> ids = service.getActionableUserIds(10L);
        assertTrue(ids.contains(20L),  "the director's direct report");
        assertTrue(ids.contains(208L), "a report of a report must resolve too");
        assertTrue(ids.contains(301L), "the other branch beneath the director as well");
        assertEquals(world.size() - 2, ids.size(),
            "the director sees everyone except SUPERADMIN and the unattached telecaller");
    }

    @Test
    void leafUserSeesOnlyThemselves() {
        assertEquals(Set.of(40L), service.getActionableUserIds(40L),
            "no manager and no reports resolves to self, never to an empty list");
    }

    @Test
    void reportsWithABlankTeamStillAppear() {
        // The team column is not populated anywhere in this fixture — it is a label
        // now, and nothing about visibility may read it.
        for (Long id : service.getActionableUserIds(20L)) {
            assertTrue(world.get(id).getTeam() == null);
        }
        assertEquals(9, service.getActionableUserIds(20L).size());
    }

    @Test
    void aCycleInTheManagerColumnTerminates() {
        // Hand-maintained manager_id can loop. The walk must stop, not hang.
        world.get(10L).setManagerId(208L);          // director "reports to" one of the grandchildren
        when(usersRepo.findActiveReportingEdges()).thenReturn(edges());

        Set<Long> ids = service.getActionableUserIds(20L);
        assertTrue(ids.contains(10L), "the loop is walked once");
        assertTrue(ids.contains(30L), "and everything hanging off it");
        assertEquals(world.size() - 2, ids.size(), "but every user appears exactly once");
    }

    @Test
    void unknownActingUserResolvesToNobody() {
        assertTrue(service.getActionableUserIds(9999L).isEmpty());
        assertTrue(service.getActionableUserIds(null).isEmpty());
    }

    // ── C4 — the write-side gate ──────────────────────────────────────────────

    @Test
    void canActOnAcceptsOwnSubtreeAndRejectsOutsideIt() {
        assertTrue(service.canActOn(20L, 20L),   "yourself");
        assertTrue(service.canActOn(20L, 205L),  "your own report");
        assertFalse(service.canActOn(20L, 301L), "somebody else's report");
        assertFalse(service.canActOn(20L, 10L),  "your own manager");
    }

    @Test
    void canActOnAllowsClearingAnAssignment() {
        assertTrue(service.canActOn(20L, null),
            "a null target removes an assignment — that is not an escalation");
    }

    @Test
    void topLevelPassesTheGateForAnyTarget() {
        assertTrue(service.canActOn(1L, 301L));
        assertTrue(service.canActOn(1L, 40L));
    }

    // ── Ordering of the dropdown ──────────────────────────────────────────────

    @Test
    void assignableListPutsTheCallerFirstThenSortsByName() {
        List<UsersEntity> users = service.getActionableUsers(20L);
        assertEquals(20L, users.get(0).getId(), "you can always assign to yourself, at the top");
        List<String> rest = users.subList(1, users.size()).stream().map(UsersEntity::getName).toList();
        List<String> sorted = new ArrayList<>(rest);
        sorted.sort(String::compareToIgnoreCase);
        assertEquals(sorted, rest);
    }
}
