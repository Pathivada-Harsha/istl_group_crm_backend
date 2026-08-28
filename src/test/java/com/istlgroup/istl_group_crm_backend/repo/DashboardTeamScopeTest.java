package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.istlgroup.istl_group_crm_backend.service.UserScopeService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The team-scoped dashboard queries execute, and the funnel they feed descends.
 *
 * <p>These are native queries with a collection parameter repeated several times
 * in one statement ({@code assigned_to IN (:userIds) OR bd_assigned_to IN
 * (:userIds) OR ...}). Nothing in a compile or a context load exercises that —
 * native SQL is only parsed when it runs — so this test is what proves the
 * expansion works against the real database rather than only in principle.
 *
 * <p>It asserts SHAPE, not values: every stage must be a subset of the stage
 * above it, whatever rows the environment happens to hold. That is the property
 * the funnel drawing depends on, and it is the one the old current-status counts
 * could not provide. It therefore passes on an empty database too.
 */
@SpringBootTest
class DashboardTeamScopeTest {

    @Autowired DashboardRepo    repo;
    @Autowired UserScopeService userScopeService;
    @Autowired UsersRepo        usersRepo;

    /** Some real user; the queries are exercised against whoever exists here. */
    private Long anyUserId() {
        List<Object[]> edges = usersRepo.findActiveReportingEdges();
        assertThat(edges).as("no active users to scope against").isNotEmpty();
        return ((Number) edges.get(0)[0]).longValue();
    }

    @Test
    @DisplayName("every team-scoped query runs, and the funnel can only descend")
    void teamFunnelDescends() {
        Long      userId = anyUserId();
        Set<Long> team   = userScopeService.getActionableUserIds(userId);
        assertThat(team).as("a user always scopes to at least themselves").isNotEmpty();

        long total      = repo.countLeadsForTeam(team);
        long contacted  = repo.countReachedContactedForTeam(team);
        long discussion = repo.countReachedDiscussionForTeam(team);
        long proposal   = repo.countReachedProposalForTeam(team);
        long won        = repo.countClosedWonForTeam(team);

        assertThat(contacted).as("Contacted ⊆ Total").isLessThanOrEqualTo(total);
        assertThat(discussion).as("In Discussion ⊆ Contacted").isLessThanOrEqualTo(contacted);
        assertThat(proposal).as("Proposal Sent ⊆ In Discussion").isLessThanOrEqualTo(discussion);
        assertThat(won).as("Closed Won ⊆ Proposal Sent").isLessThanOrEqualTo(proposal);

        // The conversion rate the KPI card prints can no longer exceed 100%, which
        // is what "4 leads, 7 closed won, 175%" was.
        assertThat(won).as("Closed Won ⊆ Total").isLessThanOrEqualTo(total);
    }

    @Test
    @DisplayName("the remaining team queries execute against the real schema")
    void remainingTeamQueriesExecute() {
        Long      userId = anyUserId();
        Set<Long> team   = userScopeService.getActionableUserIds(userId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        // No assertion on the numbers — this is here to fail on a bad column name
        // or a parameter that Hibernate cannot expand.
        repo.countActiveLeadsForTeam(team);
        repo.countInDiscussionForTeam(team);
        repo.countContactedForTeam(team);
        repo.countProposalsForTeam(team);
        repo.countAcceptedProposalsForTeam(team);
        repo.sumRevenueForTeam(team);
        repo.countPendingFollowupsForTeam(team);
        repo.countOverdueFollowupsForTeam(team, now);
        repo.countTodayFollowupsForTeam(team, now);
        repo.pendingFollowupsForTeam(team);
        repo.leadsForTeam(team);
        repo.proposalsForTeam(team);
        repo.countLeadsGroupedByMonthForTeam(team, now.minusMonths(6));

        assertThat(repo.teamPerformanceForUsers(team))
            .as("the team table query returns one row per member that exists")
            .hasSizeLessThanOrEqualTo(team.size());
    }

    @Test
    @DisplayName("the BD funnel stages are a cascade too")
    void bdFunnelDescends() {
        Long userId = anyUserId();

        long leads      = repo.countLeadsForBD(userId);
        long contacted  = repo.countReachedContactedForBD(userId);
        long discussion = repo.countReachedDiscussionForBD(userId);
        long proposal   = repo.countReachedProposalForBD(userId);
        long won        = repo.countReachedWonForBD(userId);

        assertThat(contacted).isLessThanOrEqualTo(leads);
        assertThat(discussion).isLessThanOrEqualTo(contacted);
        assertThat(proposal).isLessThanOrEqualTo(discussion);
        assertThat(won).isLessThanOrEqualTo(proposal);
    }
}
