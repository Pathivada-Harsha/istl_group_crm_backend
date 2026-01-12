package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DropdownSubGroupRepository extends JpaRepository<DropdownSubGroupEntity, Long> {
    
    @Query("SELECT sg FROM DropdownSubGroupEntity sg WHERE sg.group.groupName = :groupName AND sg.isActive = true")
    List<DropdownSubGroupEntity> findByGroupNameAndIsActiveTrue(@Param("groupName") String groupName);
    List<DropdownSubGroupEntity> findAll();
    
    Optional<DropdownSubGroupEntity> findBysubGroupName(String subGroupName); 

}

