package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;

@Repository
public interface UsersRepo extends JpaRepository<UsersEntity, Long> {

    @Query(value = "SELECT * FROM users WHERE is_active = 1", nativeQuery = true)
    List<UsersEntity> findAllActiveUsers();

    @Query("SELECT c FROM UsersEntity c WHERE c.user_id = :userid")
    UsersEntity isUserIdExist(@Param("userid") String userid);

    // Duplicate-email check that excludes the user being edited.
    // COUNT-based (returns boolean) — an Optional<UsersEntity> here throws
    // NonUniqueResultException ("2 results were returned") the moment the DB
    // already contains duplicate emails, crashing the very check meant to
    // catch them. A count can never throw, whatever state the data is in.
    @Query("SELECT COUNT(u) > 0 FROM UsersEntity u WHERE LOWER(u.email) = LOWER(:email) AND u.id <> :excludeId")
    boolean existsByEmailExcludingId(@Param("email") String email, @Param("excludeId") Long excludeId);

    // Duplicate-phone check that excludes the user being edited (same reason)
    @Query("SELECT COUNT(u) > 0 FROM UsersEntity u WHERE u.phone = :phone AND u.id <> :excludeId")
    boolean existsByPhoneExcludingId(@Param("phone") String phone, @Param("excludeId") Long excludeId);

    @Query("SELECT DISTINCT u.role FROM UsersEntity u ORDER BY u.role")
    List<String> findDistinctRoles();

    @Query(value = "SELECT COUNT(*) FROM users WHERE is_active = :isActive", nativeQuery = true)
    long countByIsActive(@Param("isActive") Long isActive);

    // ── Pagination (SUPERADMIN / ADMIN sees all) ──────────────────────────────

    @Query(value = "SELECT * FROM users ORDER BY id LIMIT :size OFFSET :offset", nativeQuery = true)
    List<UsersEntity> findAllWithPagination(@Param("size") int size, @Param("offset") int offset);

    @Query(value = """
        SELECT * FROM users
        WHERE (LOWER(name)  LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(phone) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
        ORDER BY id LIMIT :size OFFSET :offset
    """, nativeQuery = true)
    List<UsersEntity> searchByNameOrEmailOrUserId(
        @Param("searchTerm") String searchTerm, @Param("size") int size, @Param("offset") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM users
        WHERE (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(phone) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
    """, nativeQuery = true)
    long countSearchResults(@Param("searchTerm") String searchTerm);

    @Query(value = "SELECT * FROM users WHERE UPPER(role) = UPPER(:role) ORDER BY id LIMIT :size OFFSET :offset", nativeQuery = true)
    List<UsersEntity> findByRole(@Param("role") String role, @Param("size") int size, @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM users WHERE UPPER(role) = UPPER(:role)", nativeQuery = true)
    long countByRole(@Param("role") String role);

    @Query(value = """
        SELECT * FROM users
        WHERE (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
          AND UPPER(role) = UPPER(:role)
        ORDER BY id LIMIT :size OFFSET :offset
    """, nativeQuery = true)
    List<UsersEntity> searchByNameOrEmailOrUserIdAndRole(
        @Param("searchTerm") String searchTerm, @Param("role") String role,
        @Param("size") int size, @Param("offset") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM users
        WHERE (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
          AND UPPER(role) = UPPER(:role)
    """, nativeQuery = true)
    long countSearchResultsWithRole(@Param("searchTerm") String searchTerm, @Param("role") String role);

    // ── Hierarchy-aware: role IN list — CASE-INSENSITIVE via UPPER() ──────────
    // NOTE: We use UPPER(role) on the DB side; roles list is already uppercased
    // by RoleHierarchyService before being passed here.

    @Query(value = """
        SELECT * FROM users
        WHERE UPPER(role) IN (:roles) AND is_active = 1
        ORDER BY name LIMIT :size OFFSET :offset
    """, nativeQuery = true)
    List<UsersEntity> findByRoleIn(
        @Param("roles") List<String> roles, @Param("size") int size, @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM users WHERE UPPER(role) IN (:roles) AND is_active = 1", nativeQuery = true)
    long countByRoleIn(@Param("roles") List<String> roles);

    @Query(value = """
        SELECT * FROM users
        WHERE UPPER(role) IN (:roles) AND is_active = 1
          AND (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
        ORDER BY name LIMIT :size OFFSET :offset
    """, nativeQuery = true)
    List<UsersEntity> searchByRoleIn(
        @Param("roles") List<String> roles, @Param("searchTerm") String searchTerm,
        @Param("size") int size, @Param("offset") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM users
        WHERE UPPER(role) IN (:roles) AND is_active = 1
          AND (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
    """, nativeQuery = true)
    long countSearchByRoleIn(@Param("roles") List<String> roles, @Param("searchTerm") String searchTerm);

    // ── Followup assign-to: role IN list, same team — CASE-INSENSITIVE ────────

    /** All active users whose UPPER(role) is in the given list. */
    @Query(value = "SELECT * FROM users WHERE UPPER(role) IN (:roles) AND is_active = 1 ORDER BY name", nativeQuery = true)
    List<UsersEntity> findActiveUsersByRoles(@Param("roles") List<String> roles);

    /**
     * Same as {@link #findActiveUsersByRoles} but normalises the stored role the
     * way {@code RoleNormalizer} does — spaces and hyphens become underscores —
     * so a legacy row saved as "BD Executive" still matches "BD_EXECUTIVE".
     * The plain-UPPER query above silently misses those.
     */
    @Query(value = """
        SELECT * FROM users
         WHERE UPPER(REPLACE(REPLACE(TRIM(role),' ','_'),'-','_')) IN (:roles)
           AND is_active = 1
         ORDER BY name
    """, nativeQuery = true)
    List<UsersEntity> findActiveUsersByNormalisedRoles(@Param("roles") List<String> roles);

    /**
     * Same team restriction — only users in the same team with matching role.
     * team comparison is case-sensitive (team names are stored consistently).
     */
    @Query(value = """
        SELECT * FROM users
        WHERE UPPER(role) IN (:roles)
          AND is_active = 1
          AND team = :team
        ORDER BY name
    """, nativeQuery = true)
    List<UsersEntity> findActiveUsersByRolesAndTeam(
        @Param("roles") List<String> roles, @Param("team") String team);

    /**
     * All active users in the same team regardless of role.
     * Used by the followup-assignees endpoint so any team member can assign
     * a followup to any colleague on their team.
     */
    @Query(value = "SELECT * FROM users WHERE is_active = 1 AND team = :team ORDER BY name", nativeQuery = true)
    List<UsersEntity> findActiveUsersByTeam(@Param("team") String team);

    // ── Manager-based ─────────────────────────────────────────────────────────

    @Query(value = "SELECT * FROM users WHERE manager_id = :managerId ORDER BY name LIMIT :size OFFSET :offset", nativeQuery = true)
    List<UsersEntity> findByManagerId(@Param("managerId") Long managerId, @Param("size") int size, @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM users WHERE manager_id = :managerId", nativeQuery = true)
    long countByManagerId(@Param("managerId") Long managerId);

    /**
     * Every edge of the reporting graph, active users only: [id, manager_id].
     * One flat read is enough for {@link com.istlgroup.istl_group_crm_backend.service.UserScopeService}
     * to walk a whole subtree in memory — cheap at this org size, and immune to the
     * cycles a hand-maintained manager_id column can pick up (a recursive CTE is not).
     * manager_id may be NULL (top of a chain) or point at an inactive user.
     */
    @Query(value = "SELECT id, manager_id FROM users WHERE is_active = 1", nativeQuery = true)
    List<Object[]> findActiveReportingEdges();

    // ── Legacy created_by ─────────────────────────────────────────────────────

    @Query(value = "SELECT * FROM users WHERE created_by = :userId ORDER BY id LIMIT :size OFFSET :offset", nativeQuery = true)
    List<UsersEntity> findByCreatedBy(@Param("userId") Long userId, @Param("size") int size, @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM users WHERE created_by = :userId", nativeQuery = true)
    long countByCreatedBy(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM users WHERE created_by = :userId AND UPPER(role) = UPPER(:role) ORDER BY id LIMIT :size OFFSET :offset", nativeQuery = true)
    List<UsersEntity> findByCreatedByAndRole(@Param("userId") Long userId, @Param("role") String role, @Param("size") int size, @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM users WHERE created_by = :userId AND UPPER(role) = UPPER(:role)", nativeQuery = true)
    long countByCreatedByAndRole(@Param("userId") Long userId, @Param("role") String role);

    @Query(value = """
        SELECT * FROM users
        WHERE created_by = :userId
          AND (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
        ORDER BY id LIMIT :size OFFSET :offset
    """, nativeQuery = true)
    List<UsersEntity> searchByCreatedBy(@Param("userId") Long userId, @Param("searchTerm") String searchTerm, @Param("size") int size, @Param("offset") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM users WHERE created_by = :userId
          AND (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
    """, nativeQuery = true)
    long countSearchByCreatedBy(@Param("userId") Long userId, @Param("searchTerm") String searchTerm);

    @Query(value = """
        SELECT * FROM users
        WHERE created_by = :userId AND UPPER(role) = UPPER(:role)
          AND (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
        ORDER BY id LIMIT :size OFFSET :offset
    """, nativeQuery = true)
    List<UsersEntity> searchByCreatedByAndRole(@Param("userId") Long userId, @Param("searchTerm") String searchTerm, @Param("role") String role, @Param("size") int size, @Param("offset") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM users WHERE created_by = :userId AND UPPER(role) = UPPER(:role)
          AND (LOWER(name) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(email) LIKE LOWER(CONCAT('%',:searchTerm,'%'))
            OR LOWER(user_id) LIKE LOWER(CONCAT('%',:searchTerm,'%')))
    """, nativeQuery = true)
    long countSearchByCreatedByAndRole(@Param("userId") Long userId, @Param("searchTerm") String searchTerm, @Param("role") String role);

    // ── Misc ──────────────────────────────────────────────────────────────────

    Optional<UsersEntity> findByEmail(String email);
    Optional<UsersEntity> findByName(String name);
    List<UsersEntity>     findByNameIgnoreCase(String name);
    Optional<UsersEntity> findByPhone(String phone);
    Optional<UsersEntity> findByRole(String role);

    /**
     * Email lookup for the lead import's "Assigned To (Email)" column.
     *
     * <p>Returns a List rather than an Optional for the same reason
     * {@link #findByNameIgnoreCase(String)} does — see the duplicate-rows note at the top
     * of this interface. An Optional finder throws NonUniqueResultException the moment two
     * users share an address, and on the import path that would abort a whole spreadsheet
     * over one bad users row instead of reporting the one lead it affects. A List cannot
     * throw, so the caller can turn the ambiguity into an ordinary per-row error.
     *
     * <p>IgnoreCase makes the case-insensitivity a property of this query rather than of
     * the column's current utf8mb4_unicode_ci collation.
     */
    List<UsersEntity>     findByEmailIgnoreCase(String email);

    @Query(value = """
        SELECT m.name FROM menu_items m
        INNER JOIN role_menu_permissions rmp ON m.id = rmp.menu_id
        WHERE rmp.role_id = :roleId AND rmp.has_permission = 1
        ORDER BY m.id
    """, nativeQuery = true)
    List<String> getPermittedMenuNames(@Param("roleId") Long roleId);

    @Query("SELECT u.id FROM UsersEntity u WHERE UPPER(u.role) = 'TELECALLER' AND u.is_active = 1 ORDER BY u.id ASC")
    List<Long> findActiveTelecallerIds();

    default List<Long> findActiveTelecallerIdsByGroup(String g) { return findActiveTelecallerIds(); }
    default List<Long> findActiveTelecallerIdsByGroupAndSubGroup(String g, String sg) { return findActiveTelecallerIds(); }

    @Query(value = "SELECT id FROM users WHERE UPPER(REPLACE(role,' ','_')) = 'BD_EXECUTIVE' AND is_active = 1 ORDER BY id ASC", nativeQuery = true)
    List<Long> findActiveBDIds();

    @Query("SELECT u.email FROM UsersEntity u WHERE u.id = :userId")
    String findUserMailWithUserId(@Param("userId") Long userId);

    @Query("SELECT u.name FROM UsersEntity u WHERE u.id = :userId")
    String findUserNameWithUserId(@Param("userId") Long userId);
}