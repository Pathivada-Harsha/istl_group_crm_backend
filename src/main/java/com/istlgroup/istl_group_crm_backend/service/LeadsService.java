package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.CustomerWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadFilterRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadRequestWrapper;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

@Service
public class LeadsService {

    @Autowired
    private LeadsRepo leadsRepo;

    @Autowired
    private UsersRepo usersRepo;
    @Autowired
    private CustomersService customersService;
    @Autowired
    private FollowupsService followupsService;
    @Autowired
    private DropdownProjectService projectService;
   
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Get all leads based on user role
     * - SUPERADMIN: sees all leads
     * - ADMIN: sees all leads + their created leads
     * - Others: sees only their created leads and assigned leads
     */
    public List<LeadWrapper> getAllLeads(Long userId, String userRole, String groupName, String subGroupName) {
        List<LeadsEntity> leads;
        System.err.print(userRole);
        if ("SUPERADMIN".equalsIgnoreCase(userRole)) {
            leads = leadsRepo.findByDeletedAtIsNull();
        } else if ("ADMIN".equalsIgnoreCase(userRole)) {
            leads = leadsRepo.findByDeletedAtIsNull();
        } else {
            leads = leadsRepo.findByCreatedByOrAssignedTo(userId);
        }

        // Apply group/subgroup filters
        if (groupName != null && !groupName.isEmpty()) {
            leads = leads.stream()
                    .filter(lead -> groupName.equals(lead.getGroupName()))
                    .collect(Collectors.toList());
        }

        if (subGroupName != null && !subGroupName.isEmpty()) {
            leads = leads.stream()
                    .filter(lead -> subGroupName.equals(lead.getSubGroupName()))
                    .collect(Collectors.toList());
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Get leads with filters based on user role
     */
    public List<LeadWrapper> getFilteredLeads(Long userId, String userRole, LeadFilterRequestWrapper filterRequest) {
        List<LeadsEntity> leads;

        LocalDateTime fromDate = null;
        LocalDateTime toDate = null;

        if (filterRequest.getFromDate() != null && !filterRequest.getFromDate().isEmpty()) {
            fromDate = LocalDateTime.parse(filterRequest.getFromDate() + " 00:00:00", DATE_FORMATTER);
        }

        if (filterRequest.getToDate() != null && !filterRequest.getToDate().isEmpty()) {
            toDate = LocalDateTime.parse(filterRequest.getToDate() + " 23:59:59", DATE_FORMATTER);
        }

        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            // SuperAdmin and Admin can search all leads
            leads = leadsRepo.searchLeads(
                filterRequest.getSearchTerm(),
                filterRequest.getStatus(),
                filterRequest.getPriority(),
                filterRequest.getSource(),
                filterRequest.getGroupName(),
                filterRequest.getSubGroupName(),
                filterRequest.getAssignedTo(),
                fromDate,
                toDate
            );
        } else {
            // Regular users can only search their own leads
            leads = leadsRepo.searchLeadsForUser(
                userId,
                filterRequest.getSearchTerm(),
                filterRequest.getStatus(),
                filterRequest.getPriority(),
                filterRequest.getSource(),
                filterRequest.getGroupName(),
                filterRequest.getSubGroupName(),
                filterRequest.getAssignedTo(),
                fromDate,
                toDate
            );
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Get lead by ID with role-based access control
     */
    public LeadWrapper getLeadById(Long leadId, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));

        if (lead.getDeletedAt() != null) {
            throw new CustomException("Lead has been deleted");
        }

        // Check access permissions
        if (!hasAccessToLead(lead, userId, userRole)) {
            throw new CustomException("Access denied to this lead");
        }

        return convertToWrapper(lead);
    }

    /**
     * Create a new lead
     */
    public LeadWrapper createLead(LeadRequestWrapper requestWrapper, Long createdBy) throws CustomException {
        // Generate lead code
        String leadCode = generateLeadCode();

        LeadsEntity lead = new LeadsEntity();
        lead.setLeadCode(leadCode);
        lead.setCustomerId(requestWrapper.getCustomerId());
        lead.setName(requestWrapper.getName());
        lead.setEmail(requestWrapper.getEmail());
        lead.setPhone(requestWrapper.getPhone());
        lead.setSource(requestWrapper.getSource());
        lead.setPriority(requestWrapper.getPriority());
        lead.setStatus(requestWrapper.getStatus() != null ? requestWrapper.getStatus() : "New");
        lead.setAssignedTo(requestWrapper.getAssignedTo());
        lead.setEnquiry(requestWrapper.getEnquiry());
        lead.setGroupName(requestWrapper.getGroupName());
        lead.setSubGroupName(requestWrapper.getSubGroupName());
        lead.setCreatedBy(createdBy);

        LeadsEntity savedLead = leadsRepo.save(lead);
        return convertToWrapper(savedLead);
    }

    /**
     * Update an existing lead
     */
    public LeadWrapper updateLead(Long leadId, LeadRequestWrapper requestWrapper, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));

        if (lead.getDeletedAt() != null) {
            throw new CustomException("Cannot update deleted lead");
        }

        // Check access permissions
        if (!hasAccessToLead(lead, userId, userRole)) {
            throw new CustomException("Access denied to update this lead");
        }

        // Store old status to check if it changed
        String oldStatus = lead.getStatus();

        // Update fields
        if (requestWrapper.getCustomerId() != null) {
            lead.setCustomerId(requestWrapper.getCustomerId());
        }
        if (requestWrapper.getName() != null) {
            lead.setName(requestWrapper.getName());
        }
        if (requestWrapper.getEmail() != null) {
            lead.setEmail(requestWrapper.getEmail());
        }
        if (requestWrapper.getPhone() != null) {
            lead.setPhone(requestWrapper.getPhone());
        }
        if (requestWrapper.getSource() != null) {
            lead.setSource(requestWrapper.getSource());
        }
        if (requestWrapper.getPriority() != null) {
            lead.setPriority(requestWrapper.getPriority());
        }
        if (requestWrapper.getStatus() != null) {
            lead.setStatus(requestWrapper.getStatus());
        }
        if (requestWrapper.getAssignedTo() != null) {
            lead.setAssignedTo(requestWrapper.getAssignedTo());
        }
        if (requestWrapper.getEnquiry() != null) {
            lead.setEnquiry(requestWrapper.getEnquiry());
        }
        if (requestWrapper.getGroupName() != null) {
            lead.setGroupName(requestWrapper.getGroupName());
        }
        if (requestWrapper.getSubGroupName() != null) {
            lead.setSubGroupName(requestWrapper.getSubGroupName());
        }

        LeadsEntity updatedLead = leadsRepo.save(lead);
        
        // Check if status changed to "Closed Won"
        if (!"Closed Won".equalsIgnoreCase(oldStatus) && 
            "Closed Won".equalsIgnoreCase(updatedLead.getStatus())) {
            try {
                // Convert lead to customer
                CustomerWrapper customer = customersService.convertLeadToCustomer(updatedLead);
                
                // Update lead with customer_id
                updatedLead.setCustomerId(customer.getId());
                updatedLead = leadsRepo.save(updatedLead);
                 
                DropdownProjectEntity projectEntity =
                        projectService.createProjectFromLead(updatedLead);                // You can add a success message or log here
                System.out.println("Lead " + updatedLead.getLeadCode() + 
                                 " converted to customer " + customer.getCustomerCode() + "converted to project"+projectEntity.getProjectUniqueId());
            } catch (Exception e) {
                // Log the error but don't fail the lead update
                System.err.println("Failed to convert lead to customer: " + e.getMessage());
            }
        }

        return convertToWrapper(updatedLead);
    }

    /**
     * Soft delete a lead
     */
    public void deleteLead(Long leadId, Long userId, String userRole) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found with ID: " + leadId));

        if (lead.getDeletedAt() != null) {
            throw new CustomException("Lead already deleted");
        }

        // Check access permissions
        if (!hasAccessToLead(lead, userId, userRole)) {
            throw new CustomException("Access denied to delete this lead");
        }

        lead.setDeletedAt(LocalDateTime.now());
        leadsRepo.save(lead);
    }

    /**
     * Get leads by group name with role-based access
     */
    public List<LeadWrapper> getLeadsByGroup(String groupName, Long userId, String userRole) {
        List<LeadsEntity> leads;

        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            leads = leadsRepo.findByGroupNameAndDeletedAtIsNull(groupName);
        } else {
            leads = leadsRepo.findByCreatedByOrAssignedTo(userId).stream()
                    .filter(lead -> groupName.equals(lead.getGroupName()))
                    .collect(Collectors.toList());
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Get leads by status with role-based access
     */
    public List<LeadWrapper> getLeadsByStatus(String status, Long userId, String userRole) {
        List<LeadsEntity> leads;

        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            leads = leadsRepo.findByStatusAndDeletedAtIsNull(status);
        } else {
            leads = leadsRepo.findByCreatedByOrAssignedTo(userId).stream()
                    .filter(lead -> status.equals(lead.getStatus()))
                    .collect(Collectors.toList());
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Get leads assigned to a specific user
     */
    public List<LeadWrapper> getLeadsAssignedTo(Long assignedUserId, Long requestingUserId, String userRole) throws CustomException {
        List<LeadsEntity> leads;

        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            leads = leadsRepo.findByAssignedToAndDeletedAtIsNull(assignedUserId);
        } else {
            // Regular users can only see leads assigned to them
            if (!assignedUserId.equals(requestingUserId)) {
                throw new CustomException("Access denied: Can only view your own assigned leads");
            }
            leads = leadsRepo.findByAssignedToAndDeletedAtIsNull(assignedUserId);
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Get leads created by a specific user
     */
    public List<LeadWrapper> getLeadsCreatedBy(Long createdByUserId, Long requestingUserId, String userRole) throws CustomException {
        List<LeadsEntity> leads;

        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            leads = leadsRepo.findByCreatedByAndDeletedAtIsNull(createdByUserId);
        } else {
            // Regular users can only see their own created leads
            if (!createdByUserId.equals(requestingUserId)) {
                throw new CustomException("Access denied: Can only view your own created leads");
            }
            leads = leadsRepo.findByCreatedByAndDeletedAtIsNull(createdByUserId);
        }

        return leads.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Check if user has access to a lead
     */
    private boolean hasAccessToLead(LeadsEntity lead, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return true;
        }

        // Regular users can access leads they created or are assigned to
        return userId.equals(lead.getCreatedBy()) || userId.equals(lead.getAssignedTo());
    }

    /**
     * Generate unique lead code
     */
    private String generateLeadCode() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        
        // Get count of leads with codes starting with "LEAD-2025"
        long countInYear = leadsRepo.countByLeadCodeStartingWith("LEAD-" + year);
        long nextSequence = countInYear + 1;
        
        // Use 4 digits minimum, expands automatically beyond 9999
        return String.format("LEAD-%s-%04d", year, nextSequence);
    }

    /**
     * Convert Entity to Wrapper
     */
    private LeadWrapper convertToWrapper(LeadsEntity entity) {
        LeadWrapper wrapper = new LeadWrapper();
        wrapper.setId(entity.getId());
        wrapper.setLeadCode(entity.getLeadCode());
        wrapper.setCustomerId(entity.getCustomerId());
        wrapper.setName(entity.getName());
        wrapper.setEmail(entity.getEmail());
        wrapper.setPhone(entity.getPhone());
        wrapper.setSource(entity.getSource());
        wrapper.setPriority(entity.getPriority());
        wrapper.setStatus(entity.getStatus());
        wrapper.setEnquiry(entity.getEnquiry());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        wrapper.setAssignedTo(entity.getAssignedTo());
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        
        // Fetch user names
        if (entity.getAssignedTo() != null) {
            usersRepo.findById(entity.getAssignedTo()).ifPresent(user -> 
                wrapper.setAssignedToName(user.getName())
            );
        }
        
        if (entity.getCreatedBy() != null) {
            usersRepo.findById(entity.getCreatedBy()).ifPresent(user -> 
                wrapper.setCreatedByName(user.getName())
            );
        }
        
        // *** ADD THIS: Check for pending followups ***
        try {
            boolean hasPending = followupsService.hasLeadPendingFollowups(entity.getId());
            int count = followupsService.getPendingFollowupsCountForLead(entity.getId());
            wrapper.setHasPendingFollowups(hasPending);
            wrapper.setPendingFollowupsCount(count);
        } catch (Exception e) {
            wrapper.setHasPendingFollowups(false);
            wrapper.setPendingFollowupsCount(0);
        }
        
        return wrapper;
    }
}