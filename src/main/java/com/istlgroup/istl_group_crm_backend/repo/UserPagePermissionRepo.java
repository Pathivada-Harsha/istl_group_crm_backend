package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.UserPagePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserPagePermissionRepo extends JpaRepository<UserPagePermissionEntity, Long> {

    // Get permission NAMES where has_permission = 1 (mirrors findMenuNamesByUserId)
    @Query(value = """
        SELECT p.name
        FROM permissions p
        JOIN user_page_permissions upp ON p.id = upp.permission_id
        WHERE upp.user_id = :userId AND upp.has_permission = 1
        ORDER BY p.id ASC
    """, nativeQuery = true)
    List<String> findPermissionNamesByUserId(@Param("userId") Long userId);

    // Get permission IDs where has_permission = 1 (used when creating new users)
    @Query(value = """
        SELECT permission_id FROM user_page_permissions
        WHERE user_id = :userId AND has_permission = 1
    """, nativeQuery = true)
    List<Integer> findEnabledPermissionIdsByUserId(@Param("userId") Long userId);

    // Count enabled permissions (mirrors countMenuPermissions)
    @Query(value = """
        SELECT COUNT(*) FROM user_page_permissions
        WHERE user_id = :userId AND has_permission = 1
    """, nativeQuery = true)
    long countPagePermissions(@Param("userId") Long userId);

    // Find specific user+permission row
    @Query(value = """
        SELECT * FROM user_page_permissions
        WHERE user_id = :userId AND permission_id = :permissionId
    """, nativeQuery = true)
    UserPagePermissionEntity findByUserIdAndPermissionId(
            @Param("userId") Long userId,
            @Param("permissionId") Integer permissionId);

    // Delete all for user (called in DeleteUser — mirrors deleteAllByUserId)
    @Modifying
    @Query(value = "DELETE FROM user_page_permissions WHERE user_id = :userId",
           nativeQuery = true)
    void deleteAllByUserId(@Param("userId") Long userId);

    // Cascade delete when a permission is removed (KEY requirement)
    @Modifying
    @Query(value = "DELETE FROM user_page_permissions WHERE permission_id = :permissionId",
           nativeQuery = true)
    void deleteByPermissionId(@Param("permissionId") Integer permissionId);
    
    @Query(value = "SELECT id FROM users", nativeQuery = true)
    List<Long> findAllUserIds();
}