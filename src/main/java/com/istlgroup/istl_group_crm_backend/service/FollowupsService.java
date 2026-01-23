package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.FollowupsEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;

@Service
public class FollowupsService {
    
    @Autowired
    private FollowupsRepo followupsRepo;
    
    @Autowired
    private LeadsRepo leadsRepo;
    
    @Autowired
    private UsersRepo usersRepo;
    
    @Autowired
    private LeadHistoryService leadHistoryService;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * Create a new follow-up
     */
    public FollowupWrapper createFollowup(FollowupRequestWrapper request, Long createdBy) throws CustomException {
        // Validate scheduled time
        LocalDateTime scheduledAt = LocalDateTime.parse(request.getScheduledAt(), DATE_FORMATTER);
        
        FollowupsEntity followup = new FollowupsEntity();
        followup.setRelatedType(request.getRelatedType());
        followup.setRelatedId(request.getRelatedId());
        followup.setLeadId(request.getLeadId());
        followup.setCustomerId(request.getCustomerId());
        followup.setProjectId(request.getProjectId());
        followup.setGroupName(request.getGroupName());
        followup.setSubGroupName(request.getSubGroupName());
        followup.setFollowupType(request.getFollowupType() != null ? request.getFollowupType() : "Call");
        followup.setScheduledAt(scheduledAt);
        followup.setCreatedBy(createdBy);
        followup.setAssignedTo(request.getAssignedTo() != null ? request.getAssignedTo() : createdBy);
        followup.setStatus(request.getStatus() != null ? request.getStatus() : "Pending");
        followup.setPriority(request.getPriority() != null ? request.getPriority() : "Medium");
        followup.setNotes(request.getNotes());
        
        FollowupsEntity saved = followupsRepo.save(followup);
        
        // Add to lead history if it's a lead follow-up
        if (request.getLeadId() != null) {
            try {
                String description = String.format("Follow-up scheduled: %s on %s", 
                    followup.getFollowupType(), 
                    scheduledAt.format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")));
                leadHistoryService.addHistory(request.getLeadId(), "FOLLOWUP_ADDED", null, null, null, description, createdBy);
            } catch (Exception e) {
                // Log but don't fail
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }
        
        return convertToWrapper(saved);
    }
    
    /**
     * Update a follow-up
     */
    public FollowupWrapper updateFollowup(Long followupId, FollowupRequestWrapper request, Long userId) throws CustomException {
        FollowupsEntity followup = followupsRepo.findById(followupId)
            .orElseThrow(() -> new CustomException("Follow-up not found"));
        
        // Update fields
        if (request.getFollowupType() != null) {
            followup.setFollowupType(request.getFollowupType());
        }
        if (request.getScheduledAt() != null) {
            followup.setScheduledAt(LocalDateTime.parse(request.getScheduledAt(), DATE_FORMATTER));
        }
        if (request.getAssignedTo() != null) {
            followup.setAssignedTo(request.getAssignedTo());
        }
        if (request.getStatus() != null) {
            String oldStatus = followup.getStatus();
            followup.setStatus(request.getStatus());
            
            // If completed, set completion time
            if ("Completed".equals(request.getStatus()) && !"Completed".equals(oldStatus)) {
                followup.setCompletedAt(LocalDateTime.now());
            }
        }
        if (request.getPriority() != null) {
            followup.setPriority(request.getPriority());
        }
        if (request.getNotes() != null) {
            followup.setNotes(request.getNotes());
        }
        if (request.getOutcome() != null) {
            followup.setOutcome(request.getOutcome());
        }
        
        FollowupsEntity updated = followupsRepo.save(followup);
        
        // Add to lead history if status changed to completed
        if (followup.getLeadId() != null && "Completed".equals(request.getStatus())) {
            try {
                String description = String.format("Follow-up completed: %s", followup.getFollowupType());
                if (request.getOutcome() != null && !request.getOutcome().isEmpty()) {
                    description += " - " + request.getOutcome();
                }
                leadHistoryService.addHistory(followup.getLeadId(), "FOLLOWUP_COMPLETED", null, null, null, description, userId);
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }
        
        return convertToWrapper(updated);
    }
    
    /**
     * Get follow-ups for a lead
     */
    public List<FollowupWrapper> getFollowupsForLead(Long leadId) {
        List<FollowupsEntity> followups = followupsRepo.findByLeadIdOrderByScheduledAtDesc(leadId);
        return followups.stream()
            .map(this::convertToWrapper)
            .collect(Collectors.toList());
    }
    
    /**
     * Get follow-ups assigned to a user
     */
    public List<FollowupWrapper> getFollowupsForUser(Long userId) {
        List<FollowupsEntity> followups = followupsRepo.findByAssignedToOrderByScheduledAtDesc(userId);
        return followups.stream()
            .map(this::convertToWrapper)
            .collect(Collectors.toList());
    }
    
    /**
     * Get pending follow-ups for a lead
     */
    public List<FollowupWrapper> getPendingFollowupsForLead(Long leadId) {
        List<FollowupsEntity> followups = followupsRepo.findPendingByLeadId(leadId);
        return followups.stream()
            .map(this::convertToWrapper)
            .collect(Collectors.toList());
    }
    
    /**
     * Check if lead has pending follow-ups
     */
    public boolean hasLeadPendingFollowups(Long leadId) {
        return followupsRepo.countPendingByLeadId(leadId) > 0;
    }
    
    /**
     * Get pending followups count for a lead
     */
    public int getPendingFollowupsCountForLead(Long leadId) {
        return followupsRepo.countPendingByLeadId(leadId);
    }
    
    /**
     * Get overdue follow-ups
     */
    public List<FollowupWrapper> getOverdueFollowups() {
        List<FollowupsEntity> followups = followupsRepo.findOverdueFollowups(LocalDateTime.now());
        return followups.stream()
            .map(this::convertToWrapper)
            .collect(Collectors.toList());
    }
    
    /**
     * Get today's follow-ups
     */
    public List<FollowupWrapper> getTodaysFollowups() {
        List<FollowupsEntity> followups = followupsRepo.findTodaysFollowups(LocalDateTime.now());
        return followups.stream()
            .map(this::convertToWrapper)
            .collect(Collectors.toList());
    }
    
    /**
     * Delete a follow-up
     */
    public void deleteFollowup(Long followupId, Long userId) throws CustomException {
        FollowupsEntity followup = followupsRepo.findById(followupId)
            .orElseThrow(() -> new CustomException("Follow-up not found"));
        
        followupsRepo.delete(followup);
        
        // Add to lead history
        if (followup.getLeadId() != null) {
            try {
                String description = String.format("Follow-up deleted: %s", followup.getFollowupType());
                leadHistoryService.addHistory(followup.getLeadId(), "FOLLOWUP_DELETED", null, null, null, description, userId);
            } catch (Exception e) {
                System.err.println("Failed to add history: " + e.getMessage());
            }
        }
    }
    
    /**
     * Convert Entity to Wrapper
     */
    private FollowupWrapper convertToWrapper(FollowupsEntity entity) {
        FollowupWrapper wrapper = new FollowupWrapper();
        wrapper.setId(entity.getId());
        wrapper.setRelatedType(entity.getRelatedType());
        wrapper.setRelatedId(entity.getRelatedId());
        wrapper.setLeadId(entity.getLeadId());
        wrapper.setCustomerId(entity.getCustomerId());
        wrapper.setProjectId(entity.getProjectId());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        wrapper.setFollowupType(entity.getFollowupType());
        wrapper.setScheduledAt(entity.getScheduledAt() != null ? entity.getScheduledAt().toString() : null);
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setAssignedTo(entity.getAssignedTo());
        wrapper.setStatus(entity.getStatus());
        wrapper.setPriority(entity.getPriority());
        wrapper.setCompletedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().toString() : null);
        wrapper.setNotes(entity.getNotes());
        wrapper.setOutcome(entity.getOutcome());
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
        
        // Fetch related codes
        if (entity.getLeadId() != null) {
            leadsRepo.findById(entity.getLeadId()).ifPresent(lead -> 
                wrapper.setLeadCode(lead.getLeadCode())
            );
        }
        
        return wrapper;
    }
    /**
     * Get all follow-ups (for Admin/SuperAdmin)
     */
    public List<FollowupWrapper> getAllFollowups() {
        List<FollowupsEntity> followups = followupsRepo.findAll();
        return followups.stream()
            .map(this::convertToWrapper)
            .sorted((a, b) -> b.getScheduledAt().compareTo(a.getScheduledAt()))
            .collect(Collectors.toList());
    }
}