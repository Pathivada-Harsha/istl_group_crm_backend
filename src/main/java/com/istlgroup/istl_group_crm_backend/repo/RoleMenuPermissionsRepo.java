package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.RoleMenuPermissionsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleMenuPermissionsRepo extends JpaRepository<RoleMenuPermissionsEntity, Integer> {

    @Query(value = """
        SELECT m.id AS menuId, m.name AS menuName,
               COALESCE(rmp.has_permission, 0) AS hasPermission
        FROM menu_items m
        LEFT JOIN role_menu_permissions rmp
               ON rmp.menu_id = m.id AND rmp.role_id = :roleId
        ORDER BY m.id ASC
        """, nativeQuery = true)
    List<Object[]> getRawMenuPermissionsByRoleId(@Param("roleId") Integer roleId);

    @Modifying
    @Query(value = "DELETE FROM role_menu_permissions WHERE role_id = :roleId",
           nativeQuery = true)
    void deleteByRoleId(@Param("roleId") Integer roleId);

    @Modifying
    @Query(value = """
        INSERT INTO role_menu_permissions (role_id, menu_id, has_permission)
        VALUES (:roleId, :menuId, :hasPermission)
        """, nativeQuery = true)
    void insertMenuPermission(
        @Param("roleId") Integer roleId,
        @Param("menuId") Integer menuId,
        @Param("hasPermission") Boolean hasPermission
    );

    // ── Upsert support ────────────────────────────────────────────────────────
    @Query(value = """
        SELECT * FROM role_menu_permissions
        WHERE role_id = :roleId AND menu_id = :menuId
        """, nativeQuery = true)
    RoleMenuPermissionsEntity findByRoleIdAndMenuId(
        @Param("roleId") Integer roleId,
        @Param("menuId") Integer menuId
    );

    // ── Get all role IDs ──────────────────────────────────────────────────────
    @Query(value = "SELECT id FROM roles", nativeQuery = true)
    List<Integer> findAllRoleIds();

    // ── Cascade delete by menu_id ─────────────────────────────────────────────
    @Modifying
    @Query(value = "DELETE FROM role_menu_permissions WHERE menu_id = :menuId",
           nativeQuery = true)
    void deleteByMenuId(@Param("menuId") Integer menuId);
}