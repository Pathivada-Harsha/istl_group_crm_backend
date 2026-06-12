package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.LeadHistoryEntity;

public interface LeadHistoryRepo extends JpaRepository<LeadHistoryEntity, Long> {
    
    // Find all history for a lead, ordered by newest first
    List<LeadHistoryEntity> findByLeadIdOrderByCreatedAtDesc(Long leadId);
    
    // Find history by action type
    List<LeadHistoryEntity> findByLeadIdAndActionTypeOrderByCreatedAtDesc(Long leadId, String actionType);
}