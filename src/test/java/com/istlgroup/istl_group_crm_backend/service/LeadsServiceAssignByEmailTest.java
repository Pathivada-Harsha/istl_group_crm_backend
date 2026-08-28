package com.istlgroup.istl_group_crm_backend.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.istlgroup.istl_group_crm_backend.entity.TeamEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadRequestWrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The lead import's "Assigned To (Email)" column.
 *
 * <p>The column existed in the template and in the request payload, but nothing on the
 * live {@code /leads/bulk-create} path ever read it — so a lead the importer had
 * deliberately addressed to a named person was handed to the telecaller round-robin
 * instead, silently. These tests pin the resolution down.
 *
 * <p>Two things are asserted throughout, because they are the point of the feature:
 * an unresolvable email is a REJECTED ROW and never a quiet fallback to round-robin;
 * and a teammate is authorised on team grounds so the reporting-subtree gate cannot
 * refuse a colleague.
 *
 * <p>Mockito only, no Spring context and no database — the helper under test needs just
 * two repositories, and the full-context tests are the expensive ones.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeadsServiceAssignByEmailTest {

    @Mock  UsersRepo      usersRepo;
    @Mock  TeamRepository teamRepository;
    @InjectMocks LeadsService leadsService;

    private static final Long IMPORTER = 100L;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static UsersEntity user(Long id, String name, String email, String team, long active) {
        UsersEntity u = new UsersEntity();
        u.setId(id);
        u.setName(name);
        u.setEmail(email);
        u.setTeam(team);
        u.setIs_active(active);
        return u;
    }

    private static TeamEntity team(Long id) {
        TeamEntity t = new TeamEntity();
        t.setId(id);
        return t;
    }

    private static LeadRequestWrapper rowWithEmail(String email) {
        LeadRequestWrapper w = new LeadRequestWrapper();
        w.setAssignedToEmail(email);
        return w;
    }

    /**
     * Calls the private resolver, unwrapping the reflection layer so a thrown
     * CustomException surfaces as itself rather than an InvocationTargetException.
     */
    private boolean resolve(LeadRequestWrapper w) throws Throwable {
        Method m = LeadsService.class.getDeclaredMethod(
                "resolveAssignedToEmail", LeadRequestWrapper.class, Long.class);
        m.setAccessible(true);
        try {
            return (boolean) m.invoke(leadsService, w, IMPORTER);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Importer on `importerTeam`; the emailed user on `assigneeTeam`. Neither in a junction team. */
    private void given(String importerTeam, Long assigneeId, String assigneeTeam, long assigneeActive) {
        UsersEntity importer = user(IMPORTER, "Importer", "importer@x.com", importerTeam, 1L);
        UsersEntity assignee = user(assigneeId, "Anita", "anita@x.com", assigneeTeam, assigneeActive);
        when(usersRepo.findByEmailIgnoreCase("anita@x.com")).thenReturn(List.of(assignee));
        when(usersRepo.findById(IMPORTER)).thenReturn(java.util.Optional.of(importer));
        when(usersRepo.findById(assigneeId)).thenReturn(java.util.Optional.of(assignee));
        when(teamRepository.findByMemberId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
    }

    // ── no email: nothing changes ────────────────────────────────────────────

    @Test
    @DisplayName("a blank cell leaves the lead alone, so round-robin still gets it")
    void blankEmailIsUntouched() throws Throwable {
        for (String blank : new String[] { null, "", "   " }) {
            LeadRequestWrapper w = rowWithEmail(blank);
            assertThat(resolve(w)).as("blank must not authorise anything").isFalse();
            assertThat(w.getAssignedTo()).as("blank must not assign anyone").isNull();
        }
    }

    // ── rejection cases: the row must fail, never fall back to round-robin ───

    @Test
    @DisplayName("an email matching nobody rejects the row")
    void unknownEmailRejectsTheRow() {
        when(usersRepo.findByEmailIgnoreCase("ghost@nowhere.com")).thenReturn(List.of());
        LeadRequestWrapper w = rowWithEmail("ghost@nowhere.com");

        assertThatThrownBy(() -> resolve(w))
                .hasMessageContaining("does not match any CRM user")
                .hasMessageContaining("ghost@nowhere.com");
        assertThat(w.getAssignedTo()).as("a rejected row assigns nobody").isNull();
    }

    @Test
    @DisplayName("a deactivated user rejects the row — the lead would be stranded")
    void deactivatedUserRejectsTheRow() {
        given("Sesola BD", 12L, "Sesola BD", 0L);
        assertThatThrownBy(() -> resolve(rowWithEmail("anita@x.com")))
                .hasMessageContaining("deactivated user")
                .hasMessageContaining("Anita");
    }

    @Test
    @DisplayName("two accounts sharing an address rejects rather than picking one")
    void duplicateEmailRejectsTheRow() {
        when(usersRepo.findByEmailIgnoreCase("anita@x.com")).thenReturn(List.of(
                user(12L, "Anita One", "anita@x.com", "Sesola BD", 1L),
                user(13L, "Anita Two", "anita@x.com", "Sesola BD", 1L)));
        assertThatThrownBy(() -> resolve(rowWithEmail("anita@x.com")))
                .hasMessageContaining("matches more than one CRM user");
    }

    @Test
    @DisplayName("an email disagreeing with an explicit id rejects rather than guessing")
    void conflictingAssignmentRejectsTheRow() {
        given("Sesola BD", 12L, "Sesola BD", 1L);
        LeadRequestWrapper w = rowWithEmail("anita@x.com");
        w.setAssignedTo(999L);
        assertThatThrownBy(() -> resolve(w)).hasMessageContaining("Conflicting assignment");
    }

    /** cleanImportError truncates at " [" and rewrites "Duplicate entry" — messages must survive it. */
    @Test
    @DisplayName("rejection messages survive cleanImportError untruncated")
    void messagesAreSafeForTheImportErrorCleaner() {
        when(usersRepo.findByEmailIgnoreCase("ghost@nowhere.com")).thenReturn(List.of());
        assertThatThrownBy(() -> resolve(rowWithEmail("ghost@nowhere.com")))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(" [");
                    assertThat(e.getMessage()).doesNotContain("Duplicate entry");
                });
    }

    // ── resolution + team authorisation ──────────────────────────────────────

    @Test
    @DisplayName("a teammate is resolved AND authorised on team grounds")
    void teammateIsResolvedAndAuthorised() throws Throwable {
        given("Sesola BD", 12L, "Sesola BD", 1L);
        LeadRequestWrapper w = rowWithEmail("anita@x.com");

        assertThat(resolve(w)).as("same team authorises without the subtree gate").isTrue();
        assertThat(w.getAssignedTo()).isEqualTo(12L);
    }

    @Test
    @DisplayName("team match ignores case and separators")
    void teamMatchIgnoresCaseAndSeparators() throws Throwable {
        given("Sesola BD", 12L, "sesola_bd", 1L);
        assertThat(resolve(rowWithEmail("anita@x.com"))).isTrue();
    }

    @Test
    @DisplayName("a non-teammate still resolves, but leaves the subtree gate to decide")
    void nonTeammateResolvesButIsNotTeamAuthorised() throws Throwable {
        given("Sesola BD", 12L, "Some Other Team", 1L);
        LeadRequestWrapper w = rowWithEmail("anita@x.com");

        assertThat(resolve(w)).as("different team must not self-authorise").isFalse();
        assertThat(w.getAssignedTo())
                .as("still resolved — the gate, not this method, decides whether it is allowed")
                .isEqualTo(12L);
    }

    @Test
    @DisplayName("two users with no team count as the same team, by decision")
    void blankTeamMatchesBlankTeam() throws Throwable {
        given(null, 12L, null, 1L);
        assertThat(resolve(rowWithEmail("anita@x.com"))).isTrue();
    }

    @Test
    @DisplayName("a shared team in the junction table counts, even when the column disagrees")
    void junctionTableMembershipCounts() throws Throwable {
        UsersEntity importer = user(IMPORTER, "Importer", "importer@x.com", null, 1L);
        UsersEntity assignee = user(12L, "Anita", "anita@x.com", "Sesola BD", 1L);
        when(usersRepo.findByEmailIgnoreCase("anita@x.com")).thenReturn(List.of(assignee));
        when(usersRepo.findById(IMPORTER)).thenReturn(java.util.Optional.of(importer));
        when(usersRepo.findById(12L)).thenReturn(java.util.Optional.of(assignee));
        // Columns differ (null vs "Sesola BD") but both are members of team 2.
        when(teamRepository.findByMemberId(IMPORTER)).thenReturn(List.of(team(2L)));
        when(teamRepository.findByMemberId(12L)).thenReturn(List.of(team(2L)));

        assertThat(resolve(rowWithEmail("anita@x.com")))
                .as("users.team and team_members disagree in real data; either should count")
                .isTrue();
    }

    @Test
    @DisplayName("surrounding whitespace in the cell is tolerated")
    void whitespaceIsTrimmed() throws Throwable {
        given("Sesola BD", 12L, "Sesola BD", 1L);
        LeadRequestWrapper w = rowWithEmail("   anita@x.com   ");
        assertThat(resolve(w)).isTrue();
        assertThat(w.getAssignedTo()).isEqualTo(12L);
    }
}
