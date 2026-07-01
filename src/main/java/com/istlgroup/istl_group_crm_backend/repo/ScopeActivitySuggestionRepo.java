package com.istlgroup.istl_group_crm_backend.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.ScopeActivitySuggestionEntity;

public interface ScopeActivitySuggestionRepo extends JpaRepository<ScopeActivitySuggestionEntity, Long> {
    Optional<ScopeActivitySuggestionEntity> findByNameIgnoreCase(String name);
}