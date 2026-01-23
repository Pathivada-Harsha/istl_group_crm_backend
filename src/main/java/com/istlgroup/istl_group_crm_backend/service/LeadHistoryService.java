package com.istlgroup.istl_group_crm_backend.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LeadHistoryEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadHistoryRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadHistoryWrapper;

@Service
public class LeadHistoryService {
    
    @Autowired
    private LeadHistoryRepo historyRepo;
    
    @Autowired
    private UsersRepo usersRepo;
    
    /**
     * Add history entry
     */
    public void addHistory(Long leadId, String actionType, String fieldChanged, 
                          String oldValue, String newValue, String description, Long createdBy) {
        LeadHistoryEntity history = new LeadHistoryEntity();
        history.setLeadId(leadId);
        history.setActionType(actionType);
        history.setFieldChanged(fieldChanged);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setDescription(description);
        history.setCreatedBy(createdBy);
        
        historyRepo.save(history);
    }
    
    /**
     * Get history for a lead
     */
    public List<LeadHistoryWrapper> getHistoryForLead(Long leadId) throws CustomException {
        List<LeadHistoryEntity> history = historyRepo.findByLeadIdOrderByCreatedAtDesc(leadId);
        return history.stream()
            .map(this::convertToWrapper)
            .collect(Collectors.toList());
    }
    
    /**
     * Convert Entity to Wrapper
     */
    private LeadHistoryWrapper convertToWrapper(LeadHistoryEntity entity) {
        LeadHistoryWrapper wrapper = new LeadHistoryWrapper();
        wrapper.setId(entity.getId());
        wrapper.setLeadId(entity.getLeadId());
        wrapper.setActionType(entity.getActionType());
        wrapper.setFieldChanged(entity.getFieldChanged());
        wrapper.setOldValue(entity.getOldValue());
        wrapper.setNewValue(entity.getNewValue());
        wrapper.setDescription(entity.getDescription());
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        
        // Fetch user name
        if (entity.getCreatedBy() != null) {
            usersRepo.findById(entity.getCreatedBy()).ifPresent(user -> 
                wrapper.setCreatedByName(user.getName())
            );
        }
        
        return wrapper;
    }
}