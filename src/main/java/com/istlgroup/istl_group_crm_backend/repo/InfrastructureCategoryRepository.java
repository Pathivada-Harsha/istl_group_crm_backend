package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InfrastructureCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfrastructureCategoryRepository extends JpaRepository<InfrastructureCategoryEntity, Long> {

    List<InfrastructureCategoryEntity> findByVersionIdOrderByDisplayOrder(Long versionId);
}
