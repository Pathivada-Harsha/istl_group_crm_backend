package com.istlgroup.istl_group_crm_backend.repo;


import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DropdownProjectRepository extends JpaRepository<DropdownProjectEntity, Long> {
    
    @Query("SELECT p FROM DropdownProjectEntity p " +
           "JOIN p.subGroup sg " +
           "JOIN sg.group g " +
           "WHERE g.groupName = :groupName " +
           "AND sg.subGroupName = :subGroupName " +
           "AND p.isActive = true")
    List<DropdownProjectEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName
    );
    
    Optional<DropdownProjectEntity> findByProjectUniqueId(String projectUniqueId);
    
    @Query("SELECT MAX(p.projectUniqueId) FROM DropdownProjectEntity p WHERE p.projectUniqueId LIKE :prefix%")
    String findMaxProjectIdByPrefix(@Param("prefix") String prefix);
    
    List<DropdownProjectEntity> findAll(); // Add this for admin page

	long countByProjectUniqueIdStartingWith(String prefix);
}