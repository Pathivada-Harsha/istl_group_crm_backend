package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InfrastructureMasterListVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InfrastructureMasterListVersionRepository
        extends JpaRepository<InfrastructureMasterListVersionEntity, Long> {

    Optional<InfrastructureMasterListVersionEntity> findByIsCurrentTrue();
}
