package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.UserMenuPermissionEntity;

public interface UserMenuPermissionRepo extends JpaRepository<UserMenuPermissionEntity, Long> {

	@Query(value = """
		    SELECT UPPER(m.name)
		    FROM menu_items m
		    JOIN user_menu_permissions ump ON m.id = ump.menu_id
		    WHERE ump.user_id = :userId AND ump.has_permission = 1
		""", nativeQuery = true)
		List<String> findMenuNamesByUserId(@Param("userId") Long userId);


    @Query(value = """
        SELECT COUNT(*) 
        FROM user_menu_permissions 
        WHERE user_id = :userId AND has_permission = 1
    """, nativeQuery = true)
    long countMenuPermissions(@Param("userId") Long userId);
    
    @Query(value = """
    	    SELECT m.id AS menuId, m.name AS menuName, ump.has_permission
    	    FROM menu_items m
    	    LEFT JOIN user_menu_permissions ump 
    	        ON m.id = ump.menu_id AND ump.user_id = :userId
    	""", nativeQuery = true)
    	List<Object[]> getFullMenuPermissions(@Param("userId") Long userId);
    	
    	@Query(value = """
    		    SELECT 
    		        ump.id,
    		        ump.user_id,
    		        m.id AS menu_id,
    		        m.name AS menu_name,
    		        COALESCE(ump.has_permission, 0) AS has_permission
    		    FROM menu_items m
    		    LEFT JOIN user_menu_permissions ump 
    		        ON m.id = ump.menu_id AND ump.user_id = :userId
    		""", nativeQuery = true)
    		List<Object[]> getDetailedPermissions(@Param("userId") Long userId);
    		
    		// ADD THIS — needed by UpdateMenuPermissions and DeleteUser
    		@Modifying
    		@Query(value = "DELETE FROM user_menu_permissions WHERE user_id = :userId", nativeQuery = true)
    		void deleteAllByUserId(@Param("userId") Long userId);

    		// ADD THIS — needed by UpdateMenuPermissions to look up menu IDs by name
    		@Query(value = "SELECT id, name FROM menu_items", nativeQuery = true)
    		List<Object[]> findAllMenuItems();
    		
    		@Query(value = """
    			    SELECT * FROM user_menu_permissions 
    			    WHERE user_id = :userId AND menu_id = :menuId
    			""", nativeQuery = true)
    			UserMenuPermissionEntity findByUserIdAndMenuId(
    			    @Param("userId") Long userId,
    			    @Param("menuId") Long menuId
    			);

    		// ── Cascade delete when a menu item is removed ────────────────────────
    		@Modifying
    		@Query(value = "DELETE FROM user_menu_permissions WHERE menu_id = :menuId", nativeQuery = true)
    		void deleteByMenuId(@Param("menuId") Long menuId);

    		// ── Get all user IDs ──────────────────────────────────────────────────
    		@Query(value = "SELECT id FROM users", nativeQuery = true)
    		List<Long> findAllUserIds();
}