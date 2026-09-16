package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.SanctionLimitTrancheEntity;

public interface SanctionLimitTrancheRepo extends JpaRepository<SanctionLimitTrancheEntity, Long> {

    List<SanctionLimitTrancheEntity> findByLimitIdOrderByTrancheOrderAsc(Long limitId);

    void deleteByLimitId(Long limitId);

    void deleteByLimitIdIn(List<Long> limitIds);
}
