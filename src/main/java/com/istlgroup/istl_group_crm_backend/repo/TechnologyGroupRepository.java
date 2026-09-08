package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.TechnologyGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TechnologyGroupRepository extends JpaRepository<TechnologyGroupEntity, Long> {

    List<TechnologyGroupEntity> findByIsActiveTrue();
}
