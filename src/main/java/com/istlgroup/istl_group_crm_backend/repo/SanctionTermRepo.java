package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.SanctionTermEntity;

public interface SanctionTermRepo extends JpaRepository<SanctionTermEntity, Long> {

    List<SanctionTermEntity> findBySanctionIdOrderByTermOrderAsc(Long sanctionId);

    void deleteBySanctionId(Long sanctionId);
}
