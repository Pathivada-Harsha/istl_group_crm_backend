package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceItemRepository;
import com.istlgroup.istl_group_crm_backend.entity.ProposalItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProposalItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalItemWrapper;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService;
import com.istlgroup.istl_group_crm_backend.service.OrderBookItemCatalogueService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;

@Service
public class OrderBookService {

    private static final Logger log = LoggerFactory.getLogger(OrderBookService.class);

    @Autowired
    private OrderBookRepo orderBookRepo;

    @Autowired
    private OrderBookItemRepo orderBookItemRepo;

    @Autowired
    private ProposalItemRepo proposalItemRepo;

    @Autowired
    private CustomersRepo customersRepo;

    @Autowired
    private ProposalsRepo proposalsRepo;

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private DropdownProjectService projectService;

    @Autowired
    private ProjectStatsService projectStatsService;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private OrderBookItemCatalogueService itemCatalogueService;

    @Autowired
    private RoleHierarchyService roleHierarchyService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private ProjectRepository projectRepository;

    /**
     * Walks up the user→manager chain and grants project access to each
     * manager who has a manager-tier role (level 3, e.g. SALES_MANAGER).
     *
     * Stops when:
     *  - managerId is null (no manager above)
     *  - role is L1/L2 (SUPERADMIN/ADMIN bypass the table — no grant needed)
     *  - walked more than 5 levels (safety guard against misconfigured cycles)
     *
     * @param projectId   the newly created project unique ID
     * @param createdBy   userId who created the order book
     * @param orderBookNo for logging
     */
    private void grantAccessToManagerChain(String projectId, Long createdBy, String orderBookNo) {
        Long currentUserId = createdBy;
        int depth = 0;

        while (currentUserId != null && depth < 5) {
            UsersEntity u = usersRepo.findById(currentUserId).orElse(null);
            if (u == null) break;

            Long managerId = u.getManagerId();
            if (managerId == null) break;   // reached the top

            UsersEntity manager = usersRepo.findById(managerId).orElse(null);
            if (manager == null) break;

            String managerRole = manager.getRole() != null
                    ? manager.getRole().toUpperCase() : "";

            // L1 (SUPERADMIN/ADMIN) bypass the project_access table entirely
            // — no point adding a grant they'll never need
            if (managerRole.equals("SUPERADMIN") || managerRole.equals("ADMIN")) {
                log.debug("Manager {} is L1 — skipping auto-grant", managerId);
                break;
            }

            // Grant to this manager (L3: *_MANAGER roles, or any role above the creator)
            try {
                projectAccessService.grantAccess(
                    projectId,
                    managerId,
                    createdBy,
                    "Auto-granted (manager of creator): order book " + orderBookNo
                );
                log.info("Auto-granted project access to manager | project={} | managerId={}",
                    projectId, managerId);
            } catch (Exception e) {
                log.warn("Could not auto-grant project access to manager {}: {}",
                    managerId, e.getMessage());
            }

            // Continue up the chain
            currentUserId = managerId;
            depth++;
        }
    }

    private List<Long> resolveTeamMemberIds(Long userId) {
        List<Long> ids = teamRepository.findTeamMemberIdsByUserId(userId);
        return (ids == null || ids.isEmpty()) ? java.util.Collections.singletonList(userId) : ids;
    }

    private boolean hasTeamAccess(OrderBookEntity o, List<Long> memberIds) {
        return memberIds.contains(o.getCreatedBy());
    }

    /**
     * Any role starting with "ACCOUNTS_" gets full data visibility like L1/L2.
     * UI buttons are still individually gated by pagePermissions.
     */
    private boolean isAccountsRole(String userRole) {
        return userRole != null && userRole.toUpperCase().startsWith("ACCOUNTS_");
    }

    private static final DateTimeFormatter DATE_FORMATTER     = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─────────────────────────────────────────────────────────────────────────
    // GET ALL
    // ─────────────────────────────────────────────────────────────────────────
    public Page<OrderBookWrapper> getAllOrderBooks(int page, int size,
            String groupName, String subGroupName, String projectId,
            Long userId, String userRole) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        int level = roleHierarchyService.getLevelOrder(userRole);

        // projectId filter — apply on top of scoped data (all levels)
        if (projectId != null && !projectId.isEmpty()) {
            List<OrderBookEntity> projectBooks =
                orderBookRepo.findByProjectIdAndDeletedAtIsNull(projectId);
            // further filter by user scope for non-admin, non-accounts users
            if (level > 2 && !isAccountsRole(userRole)) {
                final List<Long> memberIds = level == 3 ? resolveTeamMemberIds(userId) : null;
                projectBooks = projectBooks.stream().filter(o -> {
                    if (level == 3) return hasTeamAccess(o, memberIds);
                    return o.getCreatedBy() != null && o.getCreatedBy().equals(userId);
                }).collect(java.util.stream.Collectors.toList());
            }
            int start = (int) pageable.getOffset();
            int end   = Math.min(start + pageable.getPageSize(), projectBooks.size());
            List<OrderBookEntity> pageContent = start >= projectBooks.size()
                    ? new ArrayList<>() : projectBooks.subList(start, end);
            return new PageImpl<>(pageContent, pageable, projectBooks.size())
                    .map(this::convertToWrapper);
        }

        Page<OrderBookEntity> orderBooks;
        boolean hasGroup    = groupName    != null && !groupName.isEmpty();
        boolean hasSubGroup = subGroupName != null && !subGroupName.isEmpty();

        if (level <= 2 || isAccountsRole(userRole)) {
            // L1/L2 — all order books, optional group filter
            if (hasGroup && hasSubGroup)
                orderBooks = orderBookRepo.searchOrderBooks(null, null, groupName, subGroupName, null, null, pageable);
            else if (hasGroup)
                orderBooks = orderBookRepo.searchOrderBooks(null, null, groupName, null, null, null, pageable);
            else
                orderBooks = orderBookRepo.findByDeletedAtIsNull(pageable);

        } else if (level == 3) {
            // L3 — team-scoped + optional group/subGroup filter
            List<Long> memberIds = resolveTeamMemberIds(userId);
            orderBooks = orderBookRepo.searchOrderBooksForTeam(
                memberIds, null, null,
                hasGroup    ? groupName    : null,
                hasSubGroup ? subGroupName : null,
                null, null, pageable);

        } else {
            // L4+ — own order books + optional group/subGroup filter
            orderBooks = orderBookRepo.searchOrderBooksForUser(
                userId, null, null,
                hasGroup    ? groupName    : null,
                hasSubGroup ? subGroupName : null,
                null, null, pageable);
        }

        return orderBooks.map(this::convertToWrapper);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEARCH
    // ─────────────────────────────────────────────────────────────────────────
    public Page<OrderBookWrapper> searchOrderBooks(
            String searchTerm, String status, String groupName, String subGroupName,
            String fromDate, String toDate, String sortBy, String sortDir,
            int page, int size, Long userId, String userRole) {

        LocalDate from = (fromDate != null && !fromDate.isEmpty())
                ? LocalDate.parse(fromDate, DATE_FORMATTER) : null;
        LocalDate to   = (toDate   != null && !toDate.isEmpty())
                ? LocalDate.parse(toDate,   DATE_FORMATTER) : null;
        String sortField = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sortField));
        int level = roleHierarchyService.getLevelOrder(userRole);

        if (level <= 2 || isAccountsRole(userRole)) {
            return orderBookRepo
                    .searchOrderBooks(searchTerm, status, groupName, subGroupName, from, to, pageable)
                    .map(this::convertToWrapper);
        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            return orderBookRepo
                    .searchOrderBooksForTeam(memberIds, searchTerm, status, groupName, subGroupName, from, to, pageable)
                    .map(this::convertToWrapper);
        } else {
            return orderBookRepo
                    .searchOrderBooksForUser(userId, searchTerm, status, groupName, subGroupName, from, to, pageable)
                    .map(this::convertToWrapper);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET BY ID
    // ─────────────────────────────────────────────────────────────────────────
    public OrderBookWrapper getOrderBookById(Long id) throws CustomException {
        OrderBookEntity orderBook = orderBookRepo.findById(id)
            .orElseThrow(() -> new CustomException("Order book not found with ID: " + id));
        if (orderBook.getDeletedAt() != null) {
            throw new CustomException("Order book has been deleted");
        }
        return convertToWrapper(orderBook);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET ENTITY FOR FILE DOWNLOAD  (returns raw bytes — called by controller)
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public OrderBookEntity getOrderBookEntityForDownload(Long id) throws CustomException {
        OrderBookEntity entity = orderBookRepo.findById(id)
            .orElseThrow(() -> new CustomException("Order book not found with ID: " + id));
        if (entity.getPoFileData() == null || entity.getPoFileData().length == 0) {
            throw new CustomException("No file attached to this order book");
        }
        return entity;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET ITEMS  (with allocated / invoiced qty tracking)
    // ─────────────────────────────────────────────────────────────────────────
    public List<OrderBookItemWrapper> getOrderBookItemsWithTracking(Long orderBookId) {
        List<OrderBookItemEntity> items =
            orderBookItemRepo.findByOrderBookIdWithCalculatedFields(orderBookId);

        OrderBookEntity orderBook = orderBookRepo.findById(orderBookId).orElse(null);
        String projectId = orderBook != null ? orderBook.getProjectId() : null;

        return items.stream().map(item -> {
            OrderBookItemWrapper wrapper = convertItemToWrapper(item);

            BigDecimal allocated = BigDecimal.ZERO;
            if (projectId != null && item.getItemName() != null) {
                List<PurchaseOrderItemEntity> poItems =
                    purchaseOrderItemRepository.findByItemNameAndProjectId(
                        item.getItemName(), projectId);
                for (PurchaseOrderItemEntity poi : poItems) {
                    if (poi.getQuantity() != null) {
                        allocated = allocated.add(poi.getQuantity());
                    }
                }
            }
            wrapper.setAllocatedQty(allocated);

            BigDecimal invoiced =
                invoiceItemRepository.sumInvoicedQtyByOrderBookItemId(item.getId());
            wrapper.setInvoicedQty(invoiced != null ? invoiced : BigDecimal.ZERO);

            return wrapper;
        }).collect(Collectors.toList());
    }

    public List<OrderBookItemWrapper> getOrderBookItems(Long orderBookId) {
        return orderBookItemRepo
            .findByOrderBookIdWithCalculatedFields(orderBookId)
            .stream()
            .map(this::convertItemToWrapper)
            .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROPOSAL ITEMS
    // ─────────────────────────────────────────────────────────────────────────
    public List<ProposalItemWrapper> getProposalItems(Long proposalId) throws CustomException {
        return proposalItemRepo
            .findByProposalIdWithCalculatedFields(proposalId)
            .stream()
            .map(this::convertProposalItemToWrapper)
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getProposalBomItems(Long proposalId) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found with ID: " + proposalId));

        String bomItemsJson = proposal.getBomItems();
        if (bomItemsJson == null || bomItemsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            List<Map<String, Object>> bomItems = objectMapper.readValue(
                bomItemsJson, new TypeReference<List<Map<String, Object>>>() {});

            List<Map<String, Object>> orderBookItems = new ArrayList<>();
            int lineNo = 1;

            for (Map<String, Object> bomItem : bomItems) {
                Map<String, Object> orderItem = new HashMap<>();
                orderItem.put("lineNo",          lineNo++);
                orderItem.put("itemName",         bomItem.getOrDefault("item", ""));
                orderItem.put("specification",    bomItem.getOrDefault("specification", ""));
                orderItem.put("description",      "");
                orderItem.put("proposalItemId",   null);
                orderItem.put("quantity",         parseDecimal(bomItem.get("quantity")));
                orderItem.put("unit",             bomItem.getOrDefault("unit", "Nos"));
                orderItem.put("unitPrice",        parseDecimal(bomItem.get("rate")));
                orderItem.put("taxPercent",       bomItem.get("tax"));
                orderItem.put("discountPercent",  BigDecimal.ZERO);
                orderItem.put("itemRemarks",      "");
                orderBookItems.add(orderItem);
            }
            return orderBookItems;
        } catch (Exception e) {
            throw new CustomException("Error parsing BOM items JSON: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public OrderBookWrapper createOrderBook(OrderBookRequestWrapper request, Long createdBy)
            throws CustomException {

        if (!customersRepo.existsById(request.getCustomerId())) {
            throw new CustomException("Customer not found");
        }

        OrderBookEntity orderBook = new OrderBookEntity();
        orderBook.setOrderBookNo("TEMP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        orderBook.setCustomerId(request.getCustomerId());
        orderBook.setProposalId(request.getProposalId());
        orderBook.setLeadId(request.getLeadId());
        orderBook.setGroupName(request.getGroupName());
        orderBook.setSubGroupName(request.getSubGroupName());
        orderBook.setOrderTitle(request.getOrderTitle());
        orderBook.setOrderDescription(request.getOrderDescription());
        orderBook.setOrderDate(LocalDate.parse(request.getOrderDate(), DATE_FORMATTER));

        if (request.getExpectedDeliveryDate() != null && !request.getExpectedDeliveryDate().isEmpty()) {
            orderBook.setExpectedDeliveryDate(
                LocalDate.parse(request.getExpectedDeliveryDate(), DATE_FORMATTER));
        }

        orderBook.setPoNumber(request.getPoNumber());
        if (request.getPoDate() != null && !request.getPoDate().isEmpty()) {
            orderBook.setPoDate(LocalDate.parse(request.getPoDate(), DATE_FORMATTER));
        }

        orderBook.setAdvanceAmount(request.getAdvanceAmount() != null
            ? request.getAdvanceAmount() : BigDecimal.ZERO);
        orderBook.setStatus(request.getStatus() != null ? request.getStatus() : "Draft");
        orderBook.setRemarks(request.getRemarks());
        orderBook.setCreatedBy(createdBy);

        // First save — gets DB id
        OrderBookEntity saved = orderBookRepo.save(orderBook);
        saved.setOrderBookNo(String.format("ORD-%d-%06d",
            java.time.LocalDateTime.now().getYear(), saved.getId()));
        saved = orderBookRepo.save(saved);

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            saveOrderBookItems(saved.getId(), request.getItems());
            calculateAndUpdateTotals(saved.getId());
        }

        // ── Auto-create project, grant access, seed stats ─────────────────────
        // Run in a SEPARATE transaction so any failure here never rolls back
        // the order book save above.
        try {
            autoCreateProjectForOrderBook(saved, createdBy);
        } catch (Exception e) {
            log.error("Failed to auto-create project for order book {}: {}",
                saved.getOrderBookNo(), e.getMessage());
            // Non-fatal — order book is saved; admin can link project manually
        }

        itemCatalogueService.upsertFromItems(request.getItems());
        return convertToWrapper(orderBookRepo.findById(saved.getId()).get());
    }

    /**
     * Auto-creates the project for an order book, grants access, and seeds stats.
     * Runs in its own transaction (REQUIRES_NEW) so any exception here cannot
     * mark the calling transaction as rollback-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoCreateProjectForOrderBook(OrderBookEntity saved, Long createdBy) {
        customersRepo.findById(saved.getCustomerId()).ifPresent(customer -> {
            DropdownProjectEntity project =
                projectService.createProjectFromOrderBook(saved, customer.getName());
            saved.setProjectId(project.getProjectUniqueId());
            orderBookRepo.save(saved);
            log.info("Auto-created project {} for order book {}",
                project.getProjectUniqueId(), saved.getOrderBookNo());

            try {
                projectAccessService.grantAccess(
                    project.getProjectUniqueId(),
                    createdBy,
                    createdBy,
                    "Auto-granted: created order book " + saved.getOrderBookNo()
                );
                log.info("Auto-granted project access | project={} | userId={}",
                    project.getProjectUniqueId(), createdBy);

                grantAccessToManagerChain(
                    project.getProjectUniqueId(),
                    createdBy,
                    saved.getOrderBookNo()
                );
            } catch (Exception e) {
                log.warn("Could not auto-grant project access for order book {}: {}",
                    saved.getOrderBookNo(), e.getMessage());
            }

            try {
                projectStatsService.recalculateProjectStats(project.getProjectUniqueId());
                updateProjectCapacity(project.getProjectUniqueId());
            } catch (Exception e) {
                log.warn("Could not seed project stats for {}: {}", project.getProjectUniqueId(), e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public OrderBookWrapper updateOrderBook(Long id, OrderBookRequestWrapper request, Long userId)
            throws CustomException {

        OrderBookEntity orderBook = orderBookRepo.findById(id)
            .orElseThrow(() -> new CustomException("Order book not found with ID: " + id));
        if (orderBook.getDeletedAt() != null) {
            throw new CustomException("Cannot update deleted order book");
        }

        if (request.getOrderTitle()       != null) orderBook.setOrderTitle(request.getOrderTitle());
        if (request.getOrderDescription() != null) orderBook.setOrderDescription(request.getOrderDescription());
        if (request.getOrderDate()        != null) orderBook.setOrderDate(LocalDate.parse(request.getOrderDate(), DATE_FORMATTER));

        if (request.getExpectedDeliveryDate() != null && !request.getExpectedDeliveryDate().isEmpty()) {
            orderBook.setExpectedDeliveryDate(
                LocalDate.parse(request.getExpectedDeliveryDate(), DATE_FORMATTER));
        }
        if (request.getPoNumber() != null) orderBook.setPoNumber(request.getPoNumber());
        if (request.getPoDate()   != null && !request.getPoDate().isEmpty()) {
            orderBook.setPoDate(LocalDate.parse(request.getPoDate(), DATE_FORMATTER));
        }
        if (request.getAdvanceAmount() != null) orderBook.setAdvanceAmount(request.getAdvanceAmount());
        if (request.getStatus()        != null) orderBook.setStatus(request.getStatus());
        if (request.getRemarks()       != null) orderBook.setRemarks(request.getRemarks());
        // Do NOT overwrite projectId — set by convertToProject()

        if (request.getItems() != null) {
            orderBookItemRepo.deleteByOrderBookId(id);
            saveOrderBookItems(id, request.getItems());
            calculateAndUpdateTotals(id);
        }

        OrderBookEntity updated = orderBookRepo.save(orderBook);

        if (updated.getProjectId() != null && !updated.getProjectId().isEmpty()) {
            try {
                customersRepo.findById(updated.getCustomerId()).ifPresent(customer ->
                    projectService.syncProjectNameFromOrderBook(
                        updated.getProjectId(), updated, customer.getName())
                );
                projectStatsService.recalculateProjectStats(updated.getProjectId());
                updateProjectCapacity(updated.getProjectId());
            } catch (Exception e) {
                log.warn("Could not sync project after order book update: {}", e.getMessage());
            }
        }

        itemCatalogueService.upsertFromItems(request.getItems());
        return convertToWrapper(updated);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPLOAD PO FILE  →  stored as LONGBLOB in the database
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public OrderBookWrapper uploadPOFile(Long id, MultipartFile file,
                                          String poNumber, String poDate)
            throws CustomException {

        OrderBookEntity orderBook = orderBookRepo.findById(id)
            .orElseThrow(() -> new CustomException("Order book not found with ID: " + id));
        if (orderBook.getDeletedAt() != null) {
            throw new CustomException("Cannot update deleted order book");
        }

        try {
            if (file == null || file.isEmpty()) {
                throw new CustomException("Uploaded file is empty");
            }

            // Read file bytes directly into memory — persisted as LONGBLOB
            byte[] fileBytes = file.getBytes();

            String originalFileName = file.getOriginalFilename();
            String mimeType = (file.getContentType() != null && !file.getContentType().isBlank())
                ? file.getContentType()
                : "application/octet-stream";

            orderBook.setPoFileData(fileBytes);
            orderBook.setPoFileName(originalFileName);
            orderBook.setPoFileMimeType(mimeType);
            orderBook.setPoNumber(poNumber);

            if (poDate != null && !poDate.isEmpty()) {
                orderBook.setPoDate(LocalDate.parse(poDate, DATE_FORMATTER));
            }

            OrderBookEntity updated = orderBookRepo.save(orderBook);
            return convertToWrapper(updated);

        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            throw new CustomException("Failed to upload PO file: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERT TO PROJECT
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public OrderBookWrapper convertToProject(Long orderBookId) throws CustomException {
        OrderBookEntity orderBook = orderBookRepo.findById(orderBookId)
            .orElseThrow(() -> new CustomException("Order book not found: " + orderBookId));
        if (orderBook.getDeletedAt() != null) {
            throw new CustomException("Cannot convert a deleted order book");
        }
        if (orderBook.getProjectId() != null && !orderBook.getProjectId().isEmpty()) {
            throw new CustomException(
                "This order book is already linked to project: " + orderBook.getProjectId());
        }

        CustomersEntity customer = customersRepo.findById(orderBook.getCustomerId())
            .orElseThrow(() -> new CustomException("Customer not found for order book"));

        DropdownProjectEntity project =
            projectService.createProjectFromOrderBook(orderBook, customer.getName());
        orderBook.setProjectId(project.getProjectUniqueId());
        orderBookRepo.save(orderBook);

        try {
            projectStatsService.recalculateProjectStats(project.getProjectUniqueId());
        } catch (Exception e) {
            log.warn("Could not seed project stats after conversion: {}", e.getMessage());
        }

        log.info("Order book {} converted to project {} ({})",
            orderBook.getOrderBookNo(), project.getProjectUniqueId(), project.getProjectName());

        return convertToWrapper(orderBook);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE  (soft)
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public void deleteOrderBook(Long id) throws CustomException {
        OrderBookEntity orderBook = orderBookRepo.findById(id)
            .orElseThrow(() -> new CustomException("Order book not found with ID: " + id));
        if (orderBook.getDeletedAt() != null) {
            throw new CustomException("Order book already deleted");
        }

        if (orderBook.getProjectId() != null && !orderBook.getProjectId().isEmpty()) {
            try {
                projectService.deleteProject(orderBook.getProjectId());
                log.info("Deleted project {} along with order book {}",
                    orderBook.getProjectId(), orderBook.getOrderBookNo());
            } catch (Exception e) {
                log.warn("Could not delete project {} for order book {}: {}",
                    orderBook.getProjectId(), orderBook.getOrderBookNo(), e.getMessage());
            }
        }

        orderBook.setDeletedAt(LocalDateTime.now());
        orderBookRepo.save(orderBook);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recalculate and store capacity on the project entity.
     * Sums all order_book_items.quantity across all non-deleted order books
     * for this project. Picks the unit with the highest total quantity.
     */
    private void updateProjectCapacity(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        try {
            // Fetch all non-deleted order books for this project
            List<OrderBookEntity> books = orderBookRepo.findByProjectIdAndDeletedAtIsNull(projectId);
            if (books.isEmpty()) {
                projectRepository.updateCapacity(projectId, null, null);
                return;
            }

            // Collect all item quantities grouped by unit
            Map<String, BigDecimal> unitTotals = new java.util.LinkedHashMap<>();
            for (OrderBookEntity ob : books) {
                List<OrderBookItemEntity> items =
                    orderBookItemRepo.findByOrderBookIdOrderByLineNo(ob.getId());
                for (OrderBookItemEntity item : items) {
                    if (item.getQuantity() == null) continue;
                    String rawUnit = item.getUnit() != null ? item.getUnit().trim() : "Nos";
                    // Normalise unit for grouping
                    String normUnit = normaliseCapacityUnit(ob.getSubGroupName(), rawUnit);
                    unitTotals.merge(normUnit, item.getQuantity(), BigDecimal::add);
                }
            }

            if (unitTotals.isEmpty()) {
                projectRepository.updateCapacity(projectId, null, null);
                return;
            }

            // Pick dominant unit by priority (Km > MW > kW > Units > Kg > others)
            Map.Entry<String, BigDecimal> dominant = unitTotals.entrySet().stream()
                .min(Comparator.comparingInt(e -> unitPriority(e.getKey())))
                .orElseThrow();

            projectRepository.updateCapacity(projectId, dominant.getValue(), dominant.getKey());
            log.info("Updated capacity for project {}: {} {}", projectId, dominant.getValue(), dominant.getKey());

        } catch (Exception e) {
            log.warn("Could not update capacity for project {}: {}", projectId, e.getMessage());
        }
    }

    /** Normalise unit to a clean display form for capacity storage */
    private String normaliseCapacityUnit(String subGroupName, String unit) {
        if (subGroupName != null) {
            String sg = subGroupName.toLowerCase();
            // IoT boxes → always "Units"
            if (sg.contains("ccms") || sg.contains("mcms") || sg.contains("itms")) return "Units";
        }
        if (unit == null) return "Nos";
        String u = unit.toLowerCase();
        if (u.equals("kwp") || u.equals("kw"))          return "kW";
        if (u.equals("mwp") || u.equals("mw"))          return "MW";
        if (u.equals("nos") || u.equals("units") || u.equals("pcs")) return "Units";
        if (u.equals("km"))                              return "Km";
        if (u.equals("kg") || u.equals("kgs"))          return "Kg";
        return unit;
    }

    /**
     * Priority order for choosing which unit is the "headline" capacity.
     * Lower number = higher priority (shown as main value on the card).
     */
    private int unitPriority(String unit) {
        if (unit == null) return 99;
        switch (unit) {
            case "Km":    return 1;  // Solar Wind: line length is primary
            case "MW":    return 2;
            case "kW":    return 3;
            case "Units": return 4;
            case "Kg":    return 5;
            default:      return 6;
        }
    }
    private void saveOrderBookItems(Long orderBookId, List<OrderBookItemRequestWrapper> itemRequests) {
        int lineNo = 1;
        for (OrderBookItemRequestWrapper itemRequest : itemRequests) {
            OrderBookItemEntity item = new OrderBookItemEntity();
            item.setOrderBookId(orderBookId);
            item.setLineNo(itemRequest.getLineNo() != null ? itemRequest.getLineNo() : lineNo++);
            item.setItemName(itemRequest.getItemName());
            item.setSpecification(itemRequest.getSpecification());
            item.setDescription(itemRequest.getDescription());
            item.setProposalItemId(itemRequest.getProposalItemId());
            item.setQuantity(itemRequest.getQuantity()         != null ? itemRequest.getQuantity()         : BigDecimal.ONE);
            item.setUnit(itemRequest.getUnit()                 != null ? itemRequest.getUnit()             : "Nos");
            item.setUnitPrice(itemRequest.getUnitPrice()       != null ? itemRequest.getUnitPrice()        : BigDecimal.ZERO);
            item.setTaxPercent(itemRequest.getTaxPercent()     != null ? itemRequest.getTaxPercent()       : BigDecimal.ZERO);
            item.setDiscountPercent(itemRequest.getDiscountPercent() != null
                ? itemRequest.getDiscountPercent() : BigDecimal.ZERO);
            item.setItemRemarks(itemRequest.getItemRemarks());
            orderBookItemRepo.save(item);
        }
    }

    private void calculateAndUpdateTotals(Long orderBookId) {
        List<OrderBookItemEntity> items =
            orderBookItemRepo.findByOrderBookIdWithCalculatedFields(orderBookId);

        BigDecimal subtotal   = BigDecimal.ZERO;
        BigDecimal totalTax   = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (OrderBookItemEntity item : items) {
            BigDecimal itemSubtotal = item.getQuantity().multiply(item.getUnitPrice());
            BigDecimal itemDiscount = itemSubtotal.multiply(item.getDiscountPercent())
                                        .divide(new BigDecimal("100"));
            BigDecimal itemTaxable  = itemSubtotal.subtract(itemDiscount);
            BigDecimal itemTax      = itemTaxable.multiply(item.getTaxPercent())
                                        .divide(new BigDecimal("100"));
            BigDecimal itemTotal    = itemTaxable.add(itemTax);

            subtotal   = subtotal.add(itemSubtotal);
            totalTax   = totalTax.add(itemTax);
            grandTotal = grandTotal.add(itemTotal);
        }

        OrderBookEntity orderBook = orderBookRepo.findById(orderBookId).get();
        orderBook.setSubtotal(subtotal);
        orderBook.setTaxAmount(totalTax);
        orderBook.setTotalAmount(grandTotal);
        orderBook.setBalanceAmount(grandTotal.subtract(
            orderBook.getAdvanceAmount() != null ? orderBook.getAdvanceAmount() : BigDecimal.ZERO));
        orderBookRepo.save(orderBook);
    }

    private BigDecimal parseDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Number)
            return BigDecimal.valueOf(((Number) value).doubleValue());
        if (value instanceof String) {
            try {
                String s = ((String) value).trim();
                return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERTERS
    // ─────────────────────────────────────────────────────────────────────────
    private OrderBookWrapper convertToWrapper(OrderBookEntity entity) {
        OrderBookWrapper wrapper = new OrderBookWrapper();
        wrapper.setId(entity.getId());
        wrapper.setOrderBookNo(entity.getOrderBookNo());
        wrapper.setCustomerId(entity.getCustomerId());
        wrapper.setProposalId(entity.getProposalId());
        wrapper.setLeadId(entity.getLeadId());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        wrapper.setProjectId(entity.getProjectId());
        wrapper.setOrderTitle(entity.getOrderTitle());
        wrapper.setOrderDescription(entity.getOrderDescription());
        wrapper.setOrderDate(entity.getOrderDate() != null
            ? entity.getOrderDate().toString() : null);
        wrapper.setExpectedDeliveryDate(entity.getExpectedDeliveryDate() != null
            ? entity.getExpectedDeliveryDate().toString() : null);
        wrapper.setPoNumber(entity.getPoNumber());
        wrapper.setPoDate(entity.getPoDate() != null
            ? entity.getPoDate().toString() : null);

        // ── File metadata — NEVER send raw bytes in JSON ──────────────
        wrapper.setPoFileName(entity.getPoFileName());
        wrapper.setPoFileMimeType(entity.getPoFileMimeType());
        wrapper.setHasPoFile(entity.getPoFileData() != null
            && entity.getPoFileData().length > 0);
        // ──────────────────────────────────────────────────────────────

        wrapper.setSubtotal(entity.getSubtotal());
        wrapper.setTaxAmount(entity.getTaxAmount());
        wrapper.setTotalAmount(entity.getTotalAmount());
        wrapper.setAdvanceAmount(entity.getAdvanceAmount());
        wrapper.setBalanceAmount(entity.getBalanceAmount());
        wrapper.setStatus(entity.getStatus());
        wrapper.setRemarks(entity.getRemarks());
        wrapper.setCreatedBy(entity.getCreatedBy());
        wrapper.setApprovedBy(entity.getApprovedBy());
        wrapper.setApprovedAt(entity.getApprovedAt() != null
            ? entity.getApprovedAt().toString() : null);
        wrapper.setCreatedAt(entity.getCreatedAt() != null
            ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null
            ? entity.getUpdatedAt().toString() : null);

        if (entity.getCustomerId() != null) {
            customersRepo.findById(entity.getCustomerId()).ifPresent(c -> {
                wrapper.setCustomerCode(c.getCustomerCode());
                wrapper.setCustomerName(c.getName());
            });
        }
        if (entity.getProposalId() != null) {
            proposalsRepo.findById(entity.getProposalId()).ifPresent(p ->
                wrapper.setProposalNo(p.getProposalNo()));
        }
        if (entity.getCreatedBy() != null) {
            usersRepo.findById(entity.getCreatedBy()).ifPresent(u ->
                wrapper.setCreatedByName(u.getName()));
        }
        if (entity.getApprovedBy() != null) {
            usersRepo.findById(entity.getApprovedBy()).ifPresent(u ->
                wrapper.setApprovedByName(u.getName()));
        }

        return wrapper;
    }

    private OrderBookItemWrapper convertItemToWrapper(OrderBookItemEntity entity) {
        OrderBookItemWrapper wrapper = new OrderBookItemWrapper();
        wrapper.setId(entity.getId());
        wrapper.setOrderBookId(entity.getOrderBookId());
        wrapper.setLineNo(entity.getLineNo());
        wrapper.setItemName(entity.getItemName());
        wrapper.setSpecification(entity.getSpecification());
        wrapper.setDescription(entity.getDescription());
        wrapper.setProposalItemId(entity.getProposalItemId());
        wrapper.setQuantity(entity.getQuantity());
        wrapper.setUnit(entity.getUnit());
        wrapper.setUnitPrice(entity.getUnitPrice());
        wrapper.setTaxPercent(entity.getTaxPercent());
        wrapper.setDiscountPercent(entity.getDiscountPercent());
        wrapper.setItemRemarks(entity.getItemRemarks());
        wrapper.setLineSubtotal(entity.getLineSubtotal());
        wrapper.setDiscountAmount(entity.getDiscountAmount());
        wrapper.setTaxableAmount(entity.getTaxableAmount());
        wrapper.setTaxAmount(entity.getTaxAmount());
        wrapper.setLineTotal(entity.getLineTotal());
        wrapper.setCreatedAt(entity.getCreatedAt() != null
            ? entity.getCreatedAt().toString() : null);
        wrapper.setUpdatedAt(entity.getUpdatedAt() != null
            ? entity.getUpdatedAt().toString() : null);
        return wrapper;
    }

    private ProposalItemWrapper convertProposalItemToWrapper(ProposalItemEntity entity) {
        ProposalItemWrapper wrapper = new ProposalItemWrapper();
        wrapper.setId(entity.getId());
        wrapper.setProposalId(entity.getProposalId());
        wrapper.setLineNo(entity.getLineNo());
        wrapper.setItemName(entity.getItemName());
        wrapper.setSpecification(entity.getSpecification());
        wrapper.setDescription(entity.getDescription());
        wrapper.setQuantity(entity.getQuantity());
        wrapper.setUnit(entity.getUnit());
        wrapper.setUnitPrice(entity.getUnitPrice());
        wrapper.setTaxPercent(entity.getTaxPercent());
        wrapper.setLineTotal(entity.getLineTotal());
        wrapper.setCreatedAt(entity.getCreatedAt() != null
            ? entity.getCreatedAt().toString() : null);
        return wrapper;
    }
}