package com.istlgroup.istl_group_crm_backend.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadScopeEntity;

public interface LeadScopeRepo extends JpaRepository<LeadScopeEntity, Long> {

    Optional<LeadScopeEntity> findByLeadIdAndDeletedAtIsNull(Long leadId);
}
