package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.RolesEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRoleWithStatsWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolesWrapper;

@Repository
public interface RolesRepo extends JpaRepository<RolesEntity, Integer> {
    
    @Query(value = "SELECT id FROM roles WHERE name = :roleName", nativeQuery = true)
    public Integer findRoleIdByName(@Param("roleName") String roleName);

    @Query(value = "SELECT name FROM roles ORDER BY id ASC", nativeQuery = true)
    public List<String> getAllRoles();

    @Query(value = "SELECT COUNT(*) FROM roles WHERE LOWER(name) = LOWER(:#{#newRole.name})", nativeQuery = true)
    public Integer findRole(@Param("newRole") RolesEntity newRole);

    @Query(value = "SELECT id AS id, name AS name FROM roles ORDER BY id ASC", nativeQuery = true)
    List<GetRolesWrapper> getAllRolesWithIds();

    @Query(value = """
    	    SELECT 
    	        r.id AS id,
    	        r.name AS name,
    	        r.description AS description,
    	        COUNT(DISTINCT ur.user_id) AS userCount,
    	        COUNT(DISTINCT rp.permission_id) AS permissionCount
    	    FROM roles r
    	    LEFT JOIN user_roles ur ON ur.role_id = r.id
    	    LEFT JOIN role_permissions rp ON rp.role_id = r.id
    	    GROUP BY r.id, r.name, r.description
    	    ORDER BY r.id ASC
    	    """, nativeQuery = true)
    	List<GetRoleWithStatsWrapper> getAllRolesWithStats();
	
}