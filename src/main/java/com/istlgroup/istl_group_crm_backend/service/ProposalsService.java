package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadAccessRepo;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Autowired
    private RoleHierarchyService roleHierarchyService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private LeadAccessRepo leadAccessRepo;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ── Visibility helper (same level logic as LeadsService) ─────────────────
    private List<Long> resolveTeamMemberIds(Long userId) {
        List<Long> ids = teamRepository.findTeamMemberIdsByUserId(userId);
        return (ids == null || ids.isEmpty()) ? Collections.singletonList(userId) : ids;
    }

    // ── GET ALL paginated ────────────────────────────────────────────────────
    public Page<ProposalWrapper> getAllProposalsPaginated(Long userId, String userRole,
                                                          String groupName, String subGroupName,
                                                          int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ProposalsEntity> proposalPage;

        boolean hasGroup    = groupName    != null && !groupName.isEmpty()    && !"All".equals(groupName);
        boolean hasSubGroup = subGroupName != null && !subGroupName.isEmpty() && !"All".equals(subGroupName);

        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2) {
            // L1/L2 — all proposals
            if (hasGroup && hasSubGroup)
                proposalPage = proposalsRepo.findByGroupNameAndSubGroupNameAndDeletedAtIsNull(groupName, subGroupName, pageable);
            else if (hasGroup)
                proposalPage = proposalsRepo.findByGroupNameAndDeletedAtIsNull(groupName, pageable);
            else
                proposalPage = proposalsRepo.findByDeletedAtIsNull(pageable);

        } else if (level == 3) {
            // L3 — proposals whose lead belongs to any team member
            List<Long> memberIds = resolveTeamMemberIds(userId);
            if (hasGroup && hasSubGroup)
                proposalPage = proposalsRepo.findProposalsForTeamMembersByGroupAndSubGroup(groupName, subGroupName, memberIds, pageable);
            else if (hasGroup)
                proposalPage = proposalsRepo.findProposalsForTeamMembers(groupName, memberIds, pageable);
            else
                proposalPage = proposalsRepo.findProposalsForTeamMembersWithoutGroup(memberIds, pageable);

        } else {
            // L4+ — own proposals only
            if (hasGroup && hasSubGroup)
                proposalPage = proposalsRepo.findProposalsForUserLeadsByGroupAndSubGroup(groupName, subGroupName, userId, pageable);
            else if (hasGroup)
                proposalPage = proposalsRepo.findProposalsForUserLeads(groupName, userId, pageable);
            else
                proposalPage = proposalsRepo.findProposalsForUserLeadsWithoutGroup(userId, pageable);
        }

        return proposalPage.map(this::convertToWrapper);
    }

    // ── FILTER paginated ─────────────────────────────────────────────────────
    public Page<ProposalWrapper> getFilteredProposalsPaginated(Long userId, String userRole,
                                                                ProposalRequestWrapper filterRequest,
                                                                int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        LocalDateTime fromDate = parseDate(filterRequest.getFromDate());
        LocalDateTime toDate   = parseDate(filterRequest.getToDate());
        Page<ProposalsEntity> proposalPage;

        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2) {
            proposalPage = proposalsRepo.searchProposalsPaginated(
                filterRequest.getSearchTerm(), filterRequest.getFilterStatus(),
                filterRequest.getFilterGroup(), filterRequest.getFilterSubGroup(),
                filterRequest.getFilterPreparedBy(), null, null, fromDate, toDate, pageable);

        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            proposalPage = proposalsRepo.searchProposalsForTeamPaginated(
                memberIds, filterRequest.getSearchTerm(), filterRequest.getFilterStatus(),
                filterRequest.getFilterGroup(), filterRequest.getFilterSubGroup(),
                null, null, fromDate, toDate, pageable);

        } else {
            proposalPage = proposalsRepo.searchProposalsForUserPaginated(
                userId, filterRequest.getSearchTerm(), filterRequest.getFilterStatus(),
                filterRequest.getFilterGroup(), filterRequest.getFilterSubGroup(),
                null, null, fromDate, toDate, pageable);
        }

        return proposalPage.map(this::convertToWrapper);
    }

    // ── GET BY ID ────────────────────────────────────────────────────────────
    public ProposalWrapper getProposalById(Long proposalId, Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        if (proposal.getDeletedAt() != null)
            throw new CustomException("Proposal has been deleted");
        if (!canAccessProposal(proposal, userId, userRole))
            throw new CustomException("You don't have permission to view this proposal");
        return convertToWrapper(proposal);
    }

    // ── CREATE ───────────────────────────────────────────────────────────────
    public ProposalWrapper createProposal(ProposalRequestWrapper requestWrapper, Long userId) throws CustomException {
        ProposalsEntity proposal = new ProposalsEntity();
        // proposalNo is NOT set here — assigned after first save using DB id.
        // COUNT(*)-based generation breaks when proposals are deleted.
        proposal.setLeadId(requestWrapper.getLeadId());
        proposal.setCustomerId(requestWrapper.getCustomerId());
        proposal.setTitle(requestWrapper.getTitle());
        proposal.setDescription(requestWrapper.getDescription());
        proposal.setPreparedBy(userId);
        proposal.setStatus(requestWrapper.getStatus() != null ? requestWrapper.getStatus() : "Draft");
        proposal.setTotalValue(requestWrapper.getTotalValue());
        proposal.setGroupName(requestWrapper.getGroupName());
        proposal.setSubGroupName(requestWrapper.getSubGroupName());
        proposal.setCompanyName(requestWrapper.getCompanyName() != null ?
            requestWrapper.getCompanyName() : "SESOLA POWER PROJECTS PROPOSAL PVT LTD");
        proposal.setAboutUs(requestWrapper.getAboutUs());
        proposal.setAboutSystem(requestWrapper.getAboutSystem());
        proposal.setSystemPricing(requestWrapper.getSystemPricing());
        proposal.setPaymentTerms(requestWrapper.getPaymentTerms());
        proposal.setDefectLiabilityPeriod(requestWrapper.getDefectLiabilityPeriod());
        proposal.setBomItems(requestWrapper.getBomItems());

        // First save — gets DB id
        ProposalsEntity saved = proposalsRepo.save(proposal);
        // Assign proposal number from DB id — guaranteed unique
        saved.setProposalNo(String.format("PROP-%d-%06d", LocalDateTime.now().getYear(), saved.getId()));
        saved = proposalsRepo.save(saved);
        return convertToWrapper(saved);
    }

    // ── UPDATE ───────────────────────────────────────────────────────────────
    public ProposalWrapper updateProposal(Long proposalId, ProposalRequestWrapper requestWrapper,
                                          Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        if (proposal.getDeletedAt() != null)
            throw new CustomException("Cannot update deleted proposal");
        if (!canEditProposal(proposal, userId, userRole))
            throw new CustomException("You don't have permission to edit this proposal");

        if (requestWrapper.getLeadId()              != null) proposal.setLeadId(requestWrapper.getLeadId());
        if (requestWrapper.getCustomerId()           != null) { System.err.println(requestWrapper.getCustomerId()); proposal.setCustomerId(requestWrapper.getCustomerId()); }
        if (requestWrapper.getTitle()                != null) proposal.setTitle(requestWrapper.getTitle());
        if (requestWrapper.getDescription()          != null) proposal.setDescription(requestWrapper.getDescription());
        if (requestWrapper.getStatus()               != null) proposal.setStatus(requestWrapper.getStatus());
        if (requestWrapper.getTotalValue()           != null) proposal.setTotalValue(requestWrapper.getTotalValue());
        if (requestWrapper.getGroupName()            != null) proposal.setGroupName(requestWrapper.getGroupName());
        if (requestWrapper.getSubGroupName()         != null) proposal.setSubGroupName(requestWrapper.getSubGroupName());
        if (requestWrapper.getCompanyName()          != null) proposal.setCompanyName(requestWrapper.getCompanyName());
        if (requestWrapper.getAboutUs()              != null) proposal.setAboutUs(requestWrapper.getAboutUs());
        if (requestWrapper.getAboutSystem()          != null) proposal.setAboutSystem(requestWrapper.getAboutSystem());
        if (requestWrapper.getSystemPricing()        != null) proposal.setSystemPricing(requestWrapper.getSystemPricing());
        if (requestWrapper.getPaymentTerms()         != null) proposal.setPaymentTerms(requestWrapper.getPaymentTerms());
        if (requestWrapper.getDefectLiabilityPeriod()!= null) proposal.setDefectLiabilityPeriod(requestWrapper.getDefectLiabilityPeriod());
        if (requestWrapper.getBomItems()             != null) proposal.setBomItems(requestWrapper.getBomItems());

        if (!"Draft".equals(proposal.getStatus()))
            proposal.setVersion(proposal.getVersion() + 1);

        return convertToWrapper(proposalsRepo.save(proposal));
    }

    // ── DELETE ───────────────────────────────────────────────────────────────
    public void deleteProposal(Long proposalId, Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        if (proposal.getDeletedAt() != null)
            throw new CustomException("Proposal already deleted");
        if (!canDeleteProposal(proposal, userId, userRole))
            throw new CustomException("You don't have permission to delete this proposal");
        proposal.setDeletedAt(LocalDateTime.now());
        proposalsRepo.save(proposal);
    }

    // ── Access helpers ───────────────────────────────────────────────────────
    private boolean canAccessProposal(ProposalsEntity proposal, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        // Admins and managers (level <= 2) always have access
        if (level <= 2) return true;
        // Proposal creator always has access
        if (proposal.getPreparedBy() != null && proposal.getPreparedBy().equals(userId)) return true;
        // Team members of the creator have access
        if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            if (memberIds.contains(proposal.getPreparedBy())) return true;
        }
        // If user has access to the lead this proposal belongs to, they can view the proposal
        if (proposal.getLeadId() != null && leadAccessRepo.existsByLeadIdAndUserId(proposal.getLeadId(), userId)) {
            return true;
        }
        // Anyone with level <= 4 (e.g. sales, BD) can view offline/approved proposals
        if (level <= 4 && proposal.getOfflinePdfPath() != null) return true;
        if (level <= 4 && "Approved".equalsIgnoreCase(proposal.getStatus())) return true;
        return false;
    }

    private boolean canEditProposal(ProposalsEntity proposal, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole)) return true;
        return proposalsRepo.hasProposalEditPermission(userId);
    }

    private boolean canDeleteProposal(ProposalsEntity proposal, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2) return true;
        if (proposal.getPreparedBy().equals(userId)) return true;
        if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            return memberIds.contains(proposal.getPreparedBy());
        }
        return false;
    }

    // ── Dropdown helper ──────────────────────────────────────────────────────
    public List<Map<String, Object>> getProposalsByCustomerForDropdown(Long customerId, Long userId, String userRole) {
        List<ProposalsEntity> proposals = proposalsRepo.findByCustomerIdAndDeletedAtIsNull(customerId);
        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level > 2) {
            List<Long> memberIds = (level == 3) ? resolveTeamMemberIds(userId) : Collections.singletonList(userId);
            proposals = proposals.stream()
                .filter(p -> {
                    if (p.getLeadId() != null) {
                        Optional<LeadsEntity> leadOpt = leadsRepo.findById(p.getLeadId());
                        if (leadOpt.isPresent()) {
                            LeadsEntity lead = leadOpt.get();
                            return memberIds.contains(lead.getCreatedBy()) ||
                                   (lead.getAssignedTo() != null && memberIds.contains(lead.getAssignedTo()));
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ProposalsEntity proposal : proposals) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id",          proposal.getId());
            dto.put("proposalNo",  proposal.getProposalNo());
            dto.put("title",       proposal.getTitle());
            dto.put("status",      proposal.getStatus());
            dto.put("totalValue",  proposal.getTotalValue());
            dto.put("createdAt",   proposal.getCreatedAt());
            result.add(dto);
        }
        return result;
    }

    public void updateCustomerId(Long customerId, Long leadId) {
        // Proposal is not mandatory for a lead — if none exists, silently skip.
        // Do NOT throw here; it would block the customer conversion in LeadsService.
        int updated = proposalsRepo.updateCustomerId(customerId, leadId);
        if (updated == 0) {
            System.out.println("No proposal for leadId " + leadId + " — skipping customer_id update on proposals");
        }
    }

    // ── Convert ──────────────────────────────────────────────────────────────
    /**
     * Update offline PDF path and name on a proposal.
     * Also marks status as Approved — an uploaded offline PDF is a signed/finalised document.
     */
    public ProposalWrapper updateOfflinePdf(Long proposalId, String filePath, String fileName, Long userId) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found: " + proposalId));
        proposal.setOfflinePdfPath(filePath);
        proposal.setOfflinePdfName(fileName);
        proposal.setStatus("Approved");   // offline upload = approved/finalised
        return convertToWrapper(proposalsRepo.save(proposal));
    }

    /**
     * BLOB-based variant of updateOfflinePdf.
     * Stores the file bytes in the offline_pdf_data column.
     * Clears offline_pdf_path so view-offline knows to use BLOB.
     */
    public ProposalWrapper updateOfflinePdfBlob(Long proposalId, byte[] fileData, String fileName, Long userId) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found: " + proposalId));
        proposal.setOfflinePdfData(fileData);
        proposal.setOfflinePdfName(fileName);
        proposal.setOfflinePdfPath(null);   // null = BLOB is authoritative
        proposal.setStatus("Approved");
        return convertToWrapper(proposalsRepo.save(proposal));
    }

    /**
     * Returns the raw bytes of the offline proposal PDF.
     * Priority: BLOB column → disk fallback via stored path (legacy files).
     */
    public byte[] getOfflinePdfBytes(Long proposalId) throws Exception {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found: " + proposalId));

        // New path: bytes in DB
        if (proposal.getOfflinePdfData() != null && proposal.getOfflinePdfData().length > 0) {
            return proposal.getOfflinePdfData();
        }

        // Legacy fallback: read from disk
        if (proposal.getOfflinePdfPath() != null && !proposal.getOfflinePdfPath().isBlank()) {
            String storedPath = proposal.getOfflinePdfPath();
            java.nio.file.Path direct = java.nio.file.Paths.get(storedPath.replace('\\', '/'));
            if (java.nio.file.Files.exists(direct)) {
                return java.nio.file.Files.readAllBytes(direct);
            }
            // Try relative to CWD
            java.nio.file.Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir")).resolve(storedPath.replace('\\', '/'));
            if (java.nio.file.Files.exists(cwd)) {
                return java.nio.file.Files.readAllBytes(cwd);
            }
            // Last resort: just filename under known upload dir
            String fileName = java.nio.file.Paths.get(storedPath.replace('\\', '/')).getFileName().toString();
            java.nio.file.Path fromDir = java.nio.file.Paths.get(System.getProperty("user.dir"))
                                                             .resolve("uploads/proposals/offline/")
                                                             .resolve(fileName);
            if (java.nio.file.Files.exists(fromDir)) {
                return java.nio.file.Files.readAllBytes(fromDir);
            }
        }

        return null; // caller handles null → 404
    }

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
        wrapper.setCompanyName(entity.getCompanyName());
        wrapper.setAboutUs(entity.getAboutUs());
        wrapper.setAboutSystem(entity.getAboutSystem());
        wrapper.setSystemPricing(entity.getSystemPricing());
        wrapper.setPaymentTerms(entity.getPaymentTerms());
        wrapper.setDefectLiabilityPeriod(entity.getDefectLiabilityPeriod());
        wrapper.setBomItems(entity.getBomItems());
        wrapper.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        wrapper.setOfflinePdfPath(entity.getOfflinePdfPath());
        wrapper.setOfflinePdfName(entity.getOfflinePdfName());

        if (entity.getLeadId() != null)
            leadsRepo.findById(entity.getLeadId()).ifPresent(lead -> {
                wrapper.setLeadCode(lead.getLeadCode());
                wrapper.setLeadName(lead.getName());
            });

        if (entity.getCustomerId() != null)
            customersRepo.findById(entity.getCustomerId()).ifPresent(customer -> {
                wrapper.setCustomerCode(customer.getCustomerCode());
                wrapper.setCustomerName(customer.getName());
            });

        if (entity.getPreparedBy() != null)
            usersRepo.findById(entity.getPreparedBy()).ifPresent(user ->
                wrapper.setPreparedByName(user.getName()));

        return wrapper;
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try { return LocalDateTime.parse(dateStr, formatter); }
        catch (Exception e) {
            try { return LocalDateTime.parse(dateStr); }
            catch (Exception ex) { return null; }
        }
    }
}