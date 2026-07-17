package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;

public interface LeadScopeTemplateRepo extends JpaRepository<LeadScopeTemplateEntity, Long> {

    List<LeadScopeTemplateEntity> findByDeletedAtIsNullOrderByProjectTypeAscIdAsc();

    Optional<LeadScopeTemplateEntity> findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(String projectType);

    Optional<LeadScopeTemplateEntity> findByIdAndDeletedAtIsNull(Long id);

    Page<LeadScopeTemplateEntity> findByDeletedAtIsNullAndProjectTypeContainingIgnoreCase(
            String projectType, Pageable pageable);
}
