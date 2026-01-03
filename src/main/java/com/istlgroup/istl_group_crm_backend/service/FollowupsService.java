package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FollowupsService {
    
    @Autowired
    private FollowupsRepo followupsRepo;
    
    @Autowired
    private UsersRepo usersRepo;
    
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    
    /**
     * Get followups for a specific entity (Lead/Customer)
     */
    public List<FollowupWrapper> getFollowupsForEntity(String relatedType, Long relatedId) {
        List<FollowupsEntity> followups = followupsRepo.findByRelatedTypeAndRelatedIdOrderByScheduledAtDesc(relatedType, relatedId);
        return followups.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }
    
    /**
     * Get all followups created by user
     */
    public List<FollowupWrapper> getFollowupsByCreator(Long createdBy) {
        List<FollowupsEntity> followups = followupsRepo.findByCreatedByOrderByScheduledAtDesc(createdBy);
        return followups.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }
    
    /**
     * Get all followups assigned to user
     */
    public List<FollowupWrapper> getFollowupsByAssignee(Long assignedTo) {
        List<FollowupsEntity> followups = followupsRepo.findByAssignedToOrderByScheduledAtDesc(assignedTo);
        return followups.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }
    
    /**
     * Create a new followup
     */
    public FollowupWrapper createFollowup(FollowupRequestWrapper requestWrapper, Long createdBy) throws CustomException {
        FollowupsEntity followup = new FollowupsEntity();
        followup.setRelatedType(requestWrapper.getRelatedType());
        followup.setRelatedId(requestWrapper.getRelatedId());
        followup.setFollowupType(requestWrapper.getFollowupType());
        followup.setScheduledAt(parseDateTime(requestWrapper.getScheduledAt()));
        followup.setCreatedBy(createdBy);
        followup.setAssignedTo(requestWrapper.getAssignedTo());
        followup.setStatus(requestWrapper.getStatus() != null ? requestWrapper.getStatus() : "Pending");
        followup.setNotes(requestWrapper.getNotes());
        followup.setPriority(requestWrapper.getPriority() != null ? requestWrapper.getPriority() : "Medium");
        
        if (requestWrapper.getCompletedAt() != null && !requestWrapper.getCompletedAt().isEmpty()) {
            followup.setCompletedAt(parseDateTime(requestWrapper.getCompletedAt()));
        }
        
        FollowupsEntity saved = followupsRepo.save(followup);
        return convertToWrapper(saved);
    }
    
    /**
     * Update a followup
     */
    public FollowupWrapper updateFollowup(Long followupId, FollowupRequestWrapper requestWrapper) throws CustomException {
        FollowupsEntity followup = followupsRepo.findById(followupId)
                .orElseThrow(() -> new CustomException("Followup not found"));
        
        if (requestWrapper.getRelatedType() != null) {
            followup.setRelatedType(requestWrapper.getRelatedType());
        }
        if (requestWrapper.getRelatedId() != null) {
            followup.setRelatedId(requestWrapper.getRelatedId());
        }
        if (requestWrapper.getFollowupType() != null) {
            followup.setFollowupType(requestWrapper.getFollowupType());
        }
        if (requestWrapper.getScheduledAt() != null) {
            followup.setScheduledAt(parseDateTime(requestWrapper.getScheduledAt()));
        }
        if (requestWrapper.getAssignedTo() != null) {
            followup.setAssignedTo(requestWrapper.getAssignedTo());
        }
        if (requestWrapper.getStatus() != null) {
            followup.setStatus(requestWrapper.getStatus());
            
            // If marking as completed, set completed_at
            if ("Completed".equalsIgnoreCase(requestWrapper.getStatus())) {
                followup.setCompletedAt(LocalDateTime.now());
            }
        }
        if (requestWrapper.getNotes() != null) {
            followup.setNotes(requestWrapper.getNotes());
        }
        if (requestWrapper.getPriority() != null) {
            followup.setPriority(requestWrapper.getPriority());
        }
        if (requestWrapper.getCompletedAt() != null && !requestWrapper.getCompletedAt().isEmpty()) {
            followup.setCompletedAt(parseDateTime(requestWrapper.getCompletedAt()));
        }
        
        FollowupsEntity updated = followupsRepo.save(followup);
        return convertToWrapper(updated);
    }
    
    /**
     * Delete a followup
     */
    public void deleteFollowup(Long followupId) throws CustomException {
        FollowupsEntity followup = followupsRepo.findById(followupId)
                .orElseThrow(() -> new CustomException("Followup not found"));
        followupsRepo.delete(followup);
    }
    
    /**
     * Check if lead has pending followups
     */
    public boolean hasLeadPendingFollowups(Long leadId) {
        return followupsRepo.hasPendingFollowupsForLead(leadId);
    }
    
    /**
     * Check if customer has pending followups
     */
    public boolean hasCustomerPendingFollowups(Long customerId) {
        return followupsRepo.hasPendingFollowupsForCustomer(customerId);
    }
    
    /**
     * Get pending followups count for lead
     */
    public int getPendingFollowupsCountForLead(Long leadId) {
        List<FollowupsEntity> followups = followupsRepo.findPendingFollowupsForLead(leadId);
        return followups.size();
    }
    
    /**
     * Convert entity to wrapper
     */
    private FollowupWrapper convertToWrapper(FollowupsEntity entity) {
        FollowupWrapper wrapper = new FollowupWrapper();
        wrapper.setId(entity.getId());
        wrapper.setRelatedType(entity.getRelatedType());
        wrapper.setRelatedId(entity.getRelatedId());
        wrapper.setFollowupType(entity.getFollowupType());
        wrapper.setScheduledAt(entity.getScheduledAt() != null ? entity.getScheduledAt().toString() : null);
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setAssignedTo(entity.getAssignedTo());
        wrapper.setStatus(entity.getStatus());
        wrapper.setCompletedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().toString() : null);
        wrapper.setNotes(entity.getNotes());
        wrapper.setPriority(entity.getPriority());
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        
        // Fetch user names
        if (entity.getCreatedBy() != null) {
            usersRepo.findById(entity.getCreatedBy()).ifPresent(user -> 
                wrapper.setCreatedByName(user.getName())
            );
        }
        
        if (entity.getAssignedTo() != null) {
            usersRepo.findById(entity.getAssignedTo()).ifPresent(user -> 
                wrapper.setAssignedToName(user.getName())
            );
        }
        
        return wrapper;
    }
    
    /**
     * Parse datetime string
     */
    private LocalDateTime parseDateTime(String dateTimeStr) throws CustomException {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr, formatter);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateTimeStr);
            } catch (Exception ex) {
                throw new CustomException("Invalid datetime format: " + dateTimeStr);
            }
        }
    }
}