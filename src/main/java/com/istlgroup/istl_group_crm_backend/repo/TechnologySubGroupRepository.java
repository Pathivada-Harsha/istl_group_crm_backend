package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.TechnologySubGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TechnologySubGroupRepository extends JpaRepository<TechnologySubGroupEntity, Long> {

    @Query("SELECT sg FROM TechnologySubGroupEntity sg " +
           "JOIN TechnologyGroupEntity g ON g.id = sg.groupId " +
           "WHERE g.groupName = :groupName AND sg.isActive = true " +
           "ORDER BY sg.id")
    List<TechnologySubGroupEntity> findByGroupNameAndIsActiveTrue(@Param("groupName") String groupName);
}
