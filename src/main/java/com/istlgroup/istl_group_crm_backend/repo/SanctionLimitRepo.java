package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.SanctionLimitEntity;

public interface SanctionLimitRepo extends JpaRepository<SanctionLimitEntity, Long> {

    List<SanctionLimitEntity> findBySanctionIdOrderByLimitOrderAsc(Long sanctionId);

    void deleteBySanctionId(Long sanctionId);
}
