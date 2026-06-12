package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.TeamEntity;

@Repository
public interface TeamRepository extends JpaRepository<TeamEntity, Long> {

    // Check if a team name already exists (case-insensitive), ignoring a given id
    @Query("SELECT COUNT(t) > 0 FROM TeamEntity t WHERE LOWER(t.name) = LOWER(:name) AND t.id <> :excludeId")
    boolean existsByNameIgnoreCaseExcludingId(@Param("name") String name, @Param("excludeId") Long excludeId);

    // Check if name exists at all (for create)
    @Query("SELECT COUNT(t) > 0 FROM TeamEntity t WHERE LOWER(t.name) = LOWER(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    // All teams ordered by name
    List<TeamEntity> findAllByOrderByNameAsc();

    // Teams a specific user belongs to
    @Query("SELECT t FROM TeamEntity t JOIN t.members m WHERE m.id = :userId ORDER BY t.name")
    List<TeamEntity> findByMemberId(@Param("userId") Long userId);

    Optional<TeamEntity> findById(Long id);

    // NEW — returns IDs of ALL users who share a team with the given userId,
    // including the userId themselves (DISTINCT ensures no duplicates across
    // multiple teams). Used by LeadsService for L3 team-scoped visibility.
    @Query("SELECT DISTINCT m.id FROM TeamEntity t JOIN t.members m " +
           "JOIN t.members self WHERE self.id = :userId")
    List<Long> findTeamMemberIdsByUserId(@Param("userId") Long userId);
}