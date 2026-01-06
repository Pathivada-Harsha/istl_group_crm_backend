package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalRequestWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
@Service
public class ProposalsService {
    
    @Autowired
    private ProposalsRepo proposalsRepo;
    
    @Autowired
    private LeadsRepo leadsRepo;
    
    @Autowired
    private CustomersRepo customersRepo;
    
    @Autowired
    private UsersRepo usersRepo;
    
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    
    /**
     * Get all proposals with pagination
     */
    public Page<ProposalWrapper> getAllProposalsPaginated(Long userId, String userRole,
                                                            String groupName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ProposalsEntity> proposalPage;
        
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            // Admin sees all proposals
            if (groupName != null && !groupName.isEmpty() && !"All".equals(groupName)) {
                proposalPage = proposalsRepo.findByGroupNameAndDeletedAtIsNull(groupName, pageable);
            } else {
                proposalPage = proposalsRepo.findByDeletedAtIsNull(pageable);
            }
        } else {
            // Regular users see only proposals they prepared
            if (groupName != null && !groupName.isEmpty() && !"All".equals(groupName)) {
                proposalPage = proposalsRepo.findByGroupNameAndPreparedByAndDeletedAtIsNull(groupName, userId, pageable);
            } else {
                proposalPage = proposalsRepo.findByPreparedByAndDeletedAtIsNull(userId, pageable);
            }
        }
        
        return proposalPage.map(this::convertToWrapper);
    }
    
    /**
     * Filter proposals with pagination
     */
    public Page<ProposalWrapper> getFilteredProposalsPaginated(Long userId, String userRole,
                                                                 ProposalRequestWrapper filterRequest,
                                                                 int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        LocalDateTime fromDate = parseDate(filterRequest.getFromDate());
        LocalDateTime toDate = parseDate(filterRequest.getToDate());
        
        Page<ProposalsEntity> proposalPage;
        
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            // Admin searches all proposals
            proposalPage = proposalsRepo.searchProposalsPaginated(
                filterRequest.getSearchTerm(),
                filterRequest.getFilterStatus(),
                filterRequest.getFilterGroup(),
                filterRequest.getFilterPreparedBy(),
                null, // leadId
                null, // customerId
                fromDate,
                toDate,
                pageable
            );
        } else {
            // Regular users search only their proposals
            proposalPage = proposalsRepo.searchProposalsForUserPaginated(
                userId,
                filterRequest.getSearchTerm(),
                filterRequest.getFilterStatus(),
                filterRequest.getFilterGroup(),
                null, // leadId
                null, // customerId
                fromDate,
                toDate,
                pageable
            );
        }
        
        return proposalPage.map(this::convertToWrapper);
    }
    
    /**
     * Get proposal by ID
     */
    public ProposalWrapper getProposalById(Long proposalId, Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        
        if (proposal.getDeletedAt() != null) {
            throw new CustomException("Proposal has been deleted");
        }
        
        // Check access
        if (!canAccessProposal(proposal, userId, userRole)) {
            throw new CustomException("You don't have permission to view this proposal");
        }
        
        return convertToWrapper(proposal);
    }
    
    /**
     * Create new proposal
     */
    public ProposalWrapper createProposal(ProposalRequestWrapper requestWrapper, Long userId) throws CustomException {
        ProposalsEntity proposal = new ProposalsEntity();
        
        // Generate proposal number
        String proposalNo = generateProposalNumber();
        proposal.setProposalNo(proposalNo);
        
        // Set fields
        proposal.setLeadId(requestWrapper.getLeadId());
        proposal.setCustomerId(requestWrapper.getCustomerId());
        proposal.setTitle(requestWrapper.getTitle());
        proposal.setDescription(requestWrapper.getDescription());
        proposal.setPreparedBy(userId);
        proposal.setStatus(requestWrapper.getStatus() != null ? requestWrapper.getStatus() : "Draft");
        proposal.setTotalValue(requestWrapper.getTotalValue());
        proposal.setGroupName(requestWrapper.getGroupName());
        proposal.setSubGroupName(requestWrapper.getSubGroupName());
        
        // Template fields
        proposal.setCompanyName(requestWrapper.getCompanyName() != null ? 
            requestWrapper.getCompanyName() : "SESOLA POWER PROJECTS PROPOSAL PVT LTD");
        proposal.setAboutUs(requestWrapper.getAboutUs());
        proposal.setAboutSystem(requestWrapper.getAboutSystem());
        proposal.setSystemPricing(requestWrapper.getSystemPricing());
        proposal.setPaymentTerms(requestWrapper.getPaymentTerms());
        proposal.setDefectLiabilityPeriod(requestWrapper.getDefectLiabilityPeriod());
        proposal.setBomItems(requestWrapper.getBomItems());
        
        ProposalsEntity saved = proposalsRepo.save(proposal);
        return convertToWrapper(saved);
    }
    
    /**
     * Update proposal
     */
    public ProposalWrapper updateProposal(Long proposalId, ProposalRequestWrapper requestWrapper,
                                           Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        
        if (proposal.getDeletedAt() != null) {
            throw new CustomException("Cannot update deleted proposal");
        }
        
        // Check access
        if (!canEditProposal(proposal, userId, userRole)) {
            throw new CustomException("You don't have permission to edit this proposal");
        }
        
        // Update fields
        if (requestWrapper.getLeadId() != null) {
            proposal.setLeadId(requestWrapper.getLeadId());
        }
        if (requestWrapper.getCustomerId() != null) {
            proposal.setCustomerId(requestWrapper.getCustomerId());
        }
        if (requestWrapper.getTitle() != null) {
            proposal.setTitle(requestWrapper.getTitle());
        }
        if (requestWrapper.getDescription() != null) {
            proposal.setDescription(requestWrapper.getDescription());
        }
        if (requestWrapper.getStatus() != null) {
            proposal.setStatus(requestWrapper.getStatus());
        }
        if (requestWrapper.getTotalValue() != null) {
            proposal.setTotalValue(requestWrapper.getTotalValue());
        }
        if (requestWrapper.getGroupName() != null) {
            proposal.setGroupName(requestWrapper.getGroupName());
        }
        if (requestWrapper.getSubGroupName() != null) {
            proposal.setSubGroupName(requestWrapper.getSubGroupName());
        }
        
        // Update template fields
        if (requestWrapper.getCompanyName() != null) {
            proposal.setCompanyName(requestWrapper.getCompanyName());
        }
        if (requestWrapper.getAboutUs() != null) {
            proposal.setAboutUs(requestWrapper.getAboutUs());
        }
        if (requestWrapper.getAboutSystem() != null) {
            proposal.setAboutSystem(requestWrapper.getAboutSystem());
        }
        if (requestWrapper.getSystemPricing() != null) {
            proposal.setSystemPricing(requestWrapper.getSystemPricing());
        }
        if (requestWrapper.getPaymentTerms() != null) {
            proposal.setPaymentTerms(requestWrapper.getPaymentTerms());
        }
        if (requestWrapper.getDefectLiabilityPeriod() != null) {
            proposal.setDefectLiabilityPeriod(requestWrapper.getDefectLiabilityPeriod());
        }
        if (requestWrapper.getBomItems() != null) {
            proposal.setBomItems(requestWrapper.getBomItems());
        }
        
        // Increment version if status changed from Draft
        if (!"Draft".equals(proposal.getStatus())) {
            proposal.setVersion(proposal.getVersion() + 1);
        }
        
        ProposalsEntity updated = proposalsRepo.save(proposal);
        return convertToWrapper(updated);
    }
    
    /**
     * Delete proposal (soft delete)
     */
    public void deleteProposal(Long proposalId, Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        
        if (proposal.getDeletedAt() != null) {
            throw new CustomException("Proposal already deleted");
        }
        
        // Check access
        if (!canDeleteProposal(proposal, userId, userRole)) {
            throw new CustomException("You don't have permission to delete this proposal");
        }
        
        proposal.setDeletedAt(LocalDateTime.now());
        proposalsRepo.save(proposal);
    }
    
    /**
     * Generate proposal number: PROP-YYYY-NNNN
     */
    private String generateProposalNumber() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        String prefix = "PROP-" + currentYear + "-";
        
        long count = proposalsRepo.countByProposalNoStartingWith(prefix);
        String sequence = String.format("%04d", count + 1);
        
        return prefix + sequence;
    }
    
    /**
     * Convert entity to wrapper
     */
    private ProposalWrapper convertToWrapper(ProposalsEntity entity) {
        ProposalWrapper wrapper = new ProposalWrapper();
        wrapper.setId(entity.getId());
        wrapper.setProposalNo(entity.getProposalNo());
        wrapper.setLeadId(entity.getLeadId());
        wrapper.setCustomerId(entity.getCustomerId());
        wrapper.setTitle(entity.getTitle());
        wrapper.setDescription(entity.getDescription());
        wrapper.setPreparedBy(entity.getPreparedBy());
        wrapper.setVersion(entity.getVersion());
        wrapper.setStatus(entity.getStatus());
        wrapper.setTotalValue(entity.getTotalValue());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        
        // Template fields
        wrapper.setCompanyName(entity.getCompanyName());
        wrapper.setAboutUs(entity.getAboutUs());
        wrapper.setAboutSystem(entity.getAboutSystem());
        wrapper.setSystemPricing(entity.getSystemPricing());
        wrapper.setPaymentTerms(entity.getPaymentTerms());
        wrapper.setDefectLiabilityPeriod(entity.getDefectLiabilityPeriod());
        wrapper.setBomItems(entity.getBomItems());
        
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        
        // Fetch lead info
        if (entity.getLeadId() != null) {
            leadsRepo.findById(entity.getLeadId()).ifPresent(lead -> {
                wrapper.setLeadCode(lead.getLeadCode());
                wrapper.setLeadName(lead.getName());
            });
        }
        
        // Fetch customer info
        if (entity.getCustomerId() != null) {
            customersRepo.findById(entity.getCustomerId()).ifPresent(customer -> {
                wrapper.setCustomerCode(customer.getCustomerCode());
                wrapper.setCustomerName(customer.getName());
            });
        }
        
        // Fetch prepared by user name
        if (entity.getPreparedBy() != null) {
            usersRepo.findById(entity.getPreparedBy()).ifPresent(user -> 
                wrapper.setPreparedByName(user.getName())
            );
        }
        
        return wrapper;
    }
    
    /**
     * Check if user can access proposal
     */
    private boolean canAccessProposal(ProposalsEntity proposal, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return true;
        }
        return proposal.getPreparedBy().equals(userId);
    }
    
    /**
     * Check if user can edit proposal
     */
    private boolean canEditProposal(ProposalsEntity proposal, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return true;
        }
        return proposal.getPreparedBy().equals(userId);
    }
    
    /**
     * Check if user can delete proposal
     */
    private boolean canDeleteProposal(ProposalsEntity proposal, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return true;
        }
        return proposal.getPreparedBy().equals(userId);
    }
    
    /**
     * Parse date string
     */
    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr, formatter);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateStr);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}