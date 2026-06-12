package com.istlgroup.istl_group_crm_backend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.RoleHierarchyEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleHierarchyRepo extends JpaRepository<RoleHierarchyEntity, String> {

    Optional<RoleHierarchyEntity> findByRoleName(String roleName);

    @Query(value = "SELECT * FROM role_hierarchy ORDER BY level_order ASC", nativeQuery = true)
    List<RoleHierarchyEntity> findAllOrderedByLevel();

    // NEW — returns the level_order for a given role name.
    // Used by LeadsService (and other services) to determine visibility scope
    // without hardcoding any role names.
    @Query(value = "SELECT level_order FROM role_hierarchy WHERE role_name = :roleName", nativeQuery = true)
    Optional<Integer> findLevelOrderByRoleName(@Param("roleName") String roleName);
}