package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.ReceiptEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.service.NotificationService;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Module;
import com.istlgroup.istl_group_crm_backend.constants.NotificationConstants.Type;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceRepository;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.ReceiptRepository;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;

@Service
public class CustomersService {

    @Autowired private CustomersRepo customersRepo;
    @Autowired private NotificationService notificationService;
    @Autowired private UsersRepo usersRepo;
    @Autowired private RoleHierarchyService roleHierarchyService;
    @Autowired private TeamRepository teamRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private OrderBookRepo orderBookRepo;

    private static final DateTimeFormatter DATE_FORMATTER      = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Visibility helper ────────────────────────────────────────────────────
    private List<Long> resolveTeamMemberIds(Long userId) {
        List<Long> ids = teamRepository.findTeamMemberIdsByUserId(userId);
        return (ids == null || ids.isEmpty()) ? Collections.singletonList(userId) : ids;
    }

    /**
     * Any role starting with "ACCOUNTS_" (ACCOUNTS_EXECUTIVE, ACCOUNTS_MANAGER,
     * future accounts roles) gets full data visibility identical to L1/L2.
     * UI buttons are still individually gated by pagePermissions — no bypass there.
     */
    private boolean isAccountsRole(String userRole) {
        return userRole != null && userRole.toUpperCase().startsWith("ACCOUNTS_");
    }

    // ── Dropdown helper ──────────────────────────────────────────────────────
    public List<Map<String, Object>> getCustomersByGroupForDropdown(Long userId, String userRole,
                                                                     String groupName, String subGroupName) {
        List<CustomersEntity> customers;
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2 || isAccountsRole(userRole)) {
            if (subGroupName != null && !subGroupName.trim().isEmpty())
                customers = (List<CustomersEntity>) customersRepo.findByGroupNameAndSubGroupNameAndDeletedAtIsNull(groupName, subGroupName);
            else
                customers = (List<CustomersEntity>) customersRepo.findByGroupName(groupName);
        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            if (subGroupName != null && !subGroupName.trim().isEmpty())
                customers = customersRepo.findByTeamMembersAndGroupNameAndSubGroupNameAndDeletedAtIsNull(memberIds, groupName, subGroupName);
            else
                customers = customersRepo.findByTeamMembersAndGroupNameAndDeletedAtIsNull(memberIds, groupName);
        } else {
            if (subGroupName != null && !subGroupName.trim().isEmpty())
                customers = customersRepo.findByUserAndGroupNameAndSubGroupNameAndDeletedAtIsNull(userId, groupName, subGroupName, Pageable.unpaged()).getContent();
            else
                customers = customersRepo.findByUserAndGroupNameAndDeletedAtIsNull(userId, groupName, Pageable.unpaged()).getContent();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (CustomersEntity c : customers) {
            if (c.getDeletedAt() == null) {
                Map<String, Object> dto = new HashMap<>();
                dto.put("id", c.getId());
                dto.put("customerCode", c.getCustomerCode());
                dto.put("name", c.getName());
                result.add(dto);
            }
        }
        return result;
    }

    // ── GET BY ID ────────────────────────────────────────────────────────────
    public CustomerWrapper getCustomerById(Long customerId, Long userId, String userRole) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
            .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));
        if (customer.getDeletedAt() != null) throw new CustomException("Customer has been deleted");
        if (!hasAccessToCustomer(customer, userId, userRole)) throw new CustomException("Access denied to this customer");
        return convertToWrapper(customer);
    }

    // ── CREATE ───────────────────────────────────────────────────────────────
    public CustomerWrapper createCustomer(CustomerRequestWrapper requestWrapper, Long createdBy) throws CustomException {
        CustomersEntity customer = new CustomersEntity();
        // customer_code is NOT NULL in DB — use a temp placeholder,
        // then overwrite with CUST-YYYY-id after the first save returns the DB id.
        customer.setCustomerCode("TEMP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        customer.setName(requestWrapper.getName());
        customer.setCompanyName(requestWrapper.getCompanyName());
        customer.setGroupName(requestWrapper.getGroupName());
        customer.setSubGroupName(requestWrapper.getSubGroupName());
        customer.setContactPerson(requestWrapper.getContactPerson());
        customer.setDesignation(requestWrapper.getDesignation());
        customer.setEmail(requestWrapper.getEmail());
        customer.setPhone(requestWrapper.getPhone());
        customer.setAltPhone(requestWrapper.getAltPhone());
        customer.setWebsite(requestWrapper.getWebsite());
        customer.setGstNumber(requestWrapper.getGstNumber());
        customer.setPan(requestWrapper.getPan());
        customer.setAddress(requestWrapper.getAddress());
        customer.setCity(requestWrapper.getCity());
        customer.setState(requestWrapper.getState());
        customer.setPincode(requestWrapper.getPincode());
        customer.setStatus(requestWrapper.getStatus());
        customer.setAssignedTo(requestWrapper.getAssignedTo());
        customer.setCreatedBy(createdBy);

        // Phone uniqueness check — prevent duplicate customers
        if (requestWrapper.getPhone() != null && !requestWrapper.getPhone().trim().isEmpty()) {
            if (customersRepo.existsByPhoneAndDeletedAtIsNull(requestWrapper.getPhone().trim())) {
                throw new CustomException(
                    "A customer with phone number '" + requestWrapper.getPhone() + "' already exists. " +
                    "Please check for duplicates before adding a new customer."
                );
            }
        }

        // First save — satisfies NOT NULL with temp code, gets DB id
        CustomersEntity saved = customersRepo.save(customer);
        // Now set the real code from the DB id
        saved.setCustomerCode(String.format("CUST-%d-%06d", LocalDateTime.now().getYear(), saved.getId()));
        saved = customersRepo.save(saved);

        // NOTE: Project creation is intentionally NOT done here.
        // Projects are created when the client's first Order Book is converted
        // via POST /order-book/{id}/convert-to-project.
        // This allows one client to have multiple projects (one per Order Book).
        return convertToWrapper(saved);
    }

    // ── CONVERT LEAD TO CUSTOMER ─────────────────────────────────────────────
    @Transactional
    public CustomerWrapper convertLeadToCustomer(LeadsEntity lead) throws CustomException {
        // Check if customer already exists by email
        String searchEmail = lead.getEmail();
        if (searchEmail != null && !searchEmail.isEmpty()) {
            List<CustomersEntity> existing = customersRepo.findByDeletedAtIsNull().stream()
                .filter(c -> searchEmail.equalsIgnoreCase(c.getEmail())).toList();
            if (!existing.isEmpty()) return convertToWrapper(existing.get(0));
        }

        CustomersEntity customer = new CustomersEntity();
        // customer_code is NOT NULL in DB — use a temp placeholder,
        // then overwrite with CUST-YYYY-id after the first save returns the DB id.
        customer.setCustomerCode("TEMP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        customer.setName(lead.getName());
        customer.setCompanyName(lead.getName());
        customer.setContactPerson(lead.getName());
        customer.setEmail(lead.getEmail());
        customer.setPhone(lead.getPhone());
        customer.setAssignedTo(lead.getAssignedTo());
        customer.setCreatedBy(lead.getCreatedBy());
        customer.setStatus("Active");
        customer.setGroupName(lead.getGroupName() != null && !lead.getGroupName().isEmpty() ? lead.getGroupName() : "Others");
        customer.setSubGroupName(lead.getSubGroupName());

        // First save — satisfies NOT NULL with temp code, gets DB id
        CustomersEntity saved = customersRepo.save(customer);
        // Now set the real code from the DB id
        saved.setCustomerCode(String.format("CUST-%d-%06d", LocalDateTime.now().getYear(), saved.getId()));
        CustomersEntity finalCustomer = customersRepo.save(saved);

        // ── NOTIFICATION: lead converted to customer (covers all conversion paths) ──
        try {
            Long recipient = lead.getAssignedTo() != null ? lead.getAssignedTo() : lead.getCreatedBy();
            // Do NOT notify telecallers on conversion — they are only notified
            // when a lead is assigned to them.
            boolean recipientIsTelecaller = false;
            if (recipient != null) {
                recipientIsTelecaller = usersRepo.findById(recipient)
                        .map(u -> u.getRole() != null && u.getRole().toUpperCase().contains("TELECALLER"))
                        .orElse(false);
            }
            if (recipient != null && !recipientIsTelecaller) {
                notificationService.createNotification(
                    recipient,
                    "Lead converted to customer",
                    "Lead " + lead.getLeadCode() + " was converted to customer " + finalCustomer.getCustomerCode() + ".",
                    Module.LEAD, lead.getId(), Type.LEAD_CONVERTED);
            }
        } catch (Exception e) {
            System.err.println("[CustomersService] notification error on convert: " + e.getMessage());
        }

        return convertToWrapper(finalCustomer);
    }

    // ── UPDATE ───────────────────────────────────────────────────────────────
    public CustomerWrapper updateCustomer(Long customerId, CustomerRequestWrapper requestWrapper, Long userId, String userRole) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
            .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));
        if (customer.getDeletedAt() != null) throw new CustomException("Cannot update deleted customer");
        if (!hasAccessToCustomer(customer, userId, userRole)) throw new CustomException("Access denied to update this customer");

        if (requestWrapper.getName()          != null) customer.setName(requestWrapper.getName());
        if (requestWrapper.getCompanyName()   != null) customer.setCompanyName(requestWrapper.getCompanyName());
        if (requestWrapper.getGroupName()     != null) customer.setGroupName(requestWrapper.getGroupName());
        if (requestWrapper.getSubGroupName()  != null) customer.setSubGroupName(requestWrapper.getSubGroupName());
        if (requestWrapper.getContactPerson() != null) customer.setContactPerson(requestWrapper.getContactPerson());
        if (requestWrapper.getDesignation()   != null) customer.setDesignation(requestWrapper.getDesignation());
        if (requestWrapper.getEmail()         != null) customer.setEmail(requestWrapper.getEmail());
        if (requestWrapper.getPhone()         != null) customer.setPhone(requestWrapper.getPhone());
        if (requestWrapper.getAltPhone()      != null) customer.setAltPhone(requestWrapper.getAltPhone());
        if (requestWrapper.getWebsite()       != null) customer.setWebsite(requestWrapper.getWebsite());
        if (requestWrapper.getGstNumber()     != null) customer.setGstNumber(requestWrapper.getGstNumber());
        if (requestWrapper.getPan()           != null) customer.setPan(requestWrapper.getPan());
        if (requestWrapper.getAddress()       != null) customer.setAddress(requestWrapper.getAddress());
        if (requestWrapper.getCity()          != null) customer.setCity(requestWrapper.getCity());
        if (requestWrapper.getState()         != null) customer.setState(requestWrapper.getState());
        if (requestWrapper.getPincode()       != null) customer.setPincode(requestWrapper.getPincode());
        if (requestWrapper.getStatus()        != null) customer.setStatus(requestWrapper.getStatus());
        if (requestWrapper.getAssignedTo()    != null) customer.setAssignedTo(requestWrapper.getAssignedTo());

        return convertToWrapper(customersRepo.save(customer));
    }

    // ── DELETE ───────────────────────────────────────────────────────────────
    public void deleteCustomer(Long customerId, Long userId, String userRole) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
            .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));
        if (customer.getDeletedAt() != null) throw new CustomException("Customer already deleted");
        if (!hasAccessToCustomer(customer, userId, userRole)) throw new CustomException("Access denied to delete this customer");
        customer.setDeletedAt(LocalDateTime.now());
        customersRepo.save(customer);
    }

    // ── GET ALL paginated ────────────────────────────────────────────────────
    public Page<CustomerWrapper> getAllCustomersPaginated(Long userId, String userRole,
                                                          String groupName, String subGroupName,
                                                          int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CustomersEntity> customerPage;
        int level = roleHierarchyService.getLevelOrder(userRole);
        boolean hasGroup    = groupName    != null && !groupName.isEmpty();
        boolean hasSubGroup = subGroupName != null && !subGroupName.isEmpty();

        if (level <= 2 || isAccountsRole(userRole)) {
            if (hasGroup && hasSubGroup) customerPage = customersRepo.findByGroupNameAndSubGroupNameAndDeletedAtIsNull(groupName, subGroupName, pageable);
            else if (hasGroup)          customerPage = customersRepo.findByGroupNameAndDeletedAtIsNull(groupName, pageable);
            else                        customerPage = customersRepo.findByDeletedAtIsNull(pageable);

        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            if (hasGroup && hasSubGroup) customerPage = customersRepo.findByTeamMembersAndGroupNameAndSubGroupNameAndDeletedAtIsNull(memberIds, groupName, subGroupName, pageable);
            else if (hasGroup)           customerPage = customersRepo.findByTeamMembersAndGroupNameAndDeletedAtIsNull(memberIds, groupName, pageable);
            else                         customerPage = customersRepo.findByTeamMembersAndDeletedAtIsNull(memberIds, pageable);

        } else {
            if (hasGroup && hasSubGroup) customerPage = customersRepo.findByUserAndGroupNameAndSubGroupNameAndDeletedAtIsNull(userId, groupName, subGroupName, pageable);
            else if (hasGroup)           customerPage = customersRepo.findByUserAndGroupNameAndDeletedAtIsNull(userId, groupName, pageable);
            else                         customerPage = customersRepo.findByCreatedByOrAssignedToAndDeletedAtIsNull(userId, pageable);
        }

        return customerPage.map(this::convertToWrapper);
    }

    // ── FILTER paginated ─────────────────────────────────────────────────────
    public Page<CustomerWrapper> getFilteredCustomersPaginated(Long userId, String userRole,
                                                                CustomerFilterRequestWrapper filterRequest,
                                                                int page, int size) {
        String sortField = (filterRequest.getSortBy() != null && !filterRequest.getSortBy().isBlank())
                           ? filterRequest.getSortBy() : "createdAt";
        Sort.Direction sortDir = "asc".equalsIgnoreCase(filterRequest.getSortDirection())
                                 ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDir, sortField));
        LocalDateTime fromDate = parseFromDate(filterRequest.getFromDate());
        LocalDateTime toDate   = parseToDate(filterRequest.getToDate());
        Page<CustomersEntity> customerPage;
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2 || isAccountsRole(userRole)) {
            customerPage = customersRepo.searchCustomersPaginated(
                filterRequest.getSearchTerm(), filterRequest.getGroupName(), filterRequest.getSubGroupName(),
                filterRequest.getStatus(), filterRequest.getCity(), filterRequest.getState(),
                filterRequest.getAssignedTo(), fromDate, toDate, pageable);

        } else if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            customerPage = customersRepo.searchCustomersForTeamPaginated(
                memberIds, filterRequest.getSearchTerm(), filterRequest.getGroupName(), filterRequest.getSubGroupName(),
                filterRequest.getStatus(), filterRequest.getCity(), filterRequest.getState(), fromDate, toDate, pageable);

        } else {
            customerPage = customersRepo.searchCustomersForUserPaginated(
                userId, filterRequest.getSearchTerm(), filterRequest.getGroupName(), filterRequest.getSubGroupName(),
                filterRequest.getStatus(), filterRequest.getCity(), filterRequest.getState(), fromDate, toDate, pageable);
        }

        return customerPage.map(this::convertToWrapper);
    }

    // ── Access helper ────────────────────────────────────────────────────────

    private boolean hasAccessToCustomer(CustomersEntity customer, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2 || isAccountsRole(userRole)) return true;
        if ((customer.getAssignedTo() != null && customer.getAssignedTo().equals(userId)) ||
            (customer.getCreatedBy()  != null && customer.getCreatedBy().equals(userId))) return true;
        if (level == 3) {
            List<Long> memberIds = resolveTeamMemberIds(userId);
            return memberIds.contains(customer.getCreatedBy()) || memberIds.contains(customer.getAssignedTo());
        }
        return false;
    }

    @Transactional(readOnly = true)
    public CustomersEntity getCustomerByProjectId(String projectId) {
        return customersRepo.findByProjectId(projectId)
            .orElseThrow(() -> new RuntimeException("Customer not found for project: " + projectId));
    }

    // ── Convert ──────────────────────────────────────────────────────────────
    private CustomerWrapper convertToWrapper(CustomersEntity entity) {
        CustomerWrapper wrapper = new CustomerWrapper();
        wrapper.setId(entity.getId());
        wrapper.setCustomerCode(entity.getCustomerCode());
        wrapper.setName(entity.getName());
        wrapper.setCompanyName(entity.getCompanyName());
        wrapper.setGroupName(entity.getGroupName());
        wrapper.setSubGroupName(entity.getSubGroupName());
        wrapper.setContactPerson(entity.getContactPerson());
        wrapper.setDesignation(entity.getDesignation());
        wrapper.setEmail(entity.getEmail());
        wrapper.setPhone(entity.getPhone());
        wrapper.setAltPhone(entity.getAltPhone());
        wrapper.setWebsite(entity.getWebsite());
        wrapper.setGstNumber(entity.getGstNumber());
        wrapper.setPan(entity.getPan());
        wrapper.setAddress(entity.getAddress());
        wrapper.setCity(entity.getCity());
        wrapper.setState(entity.getState());
        wrapper.setPincode(entity.getPincode());
        wrapper.setStatus(entity.getStatus());
        wrapper.setAssignedTo(entity.getAssignedTo());
        if (entity.getAssignedTo() != null)
            usersRepo.findById(entity.getAssignedTo()).ifPresent(u -> wrapper.setAssignedToName(u.getName()));
        if (entity.getCreatedAt() != null) wrapper.setCreatedAt(entity.getCreatedAt().format(DATE_FORMATTER));
        if (entity.getUpdatedAt() != null) wrapper.setUpdatedAt(entity.getUpdatedAt().format(DATE_FORMATTER));
        return wrapper;
    }

    // Parses a date string; accepts both "yyyy-MM-dd" and "yyyy-MM-dd HH:mm:ss"
    // fromDate  → start of day (00:00:00)
    private LocalDateTime parseFromDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try { return LocalDateTime.parse(dateStr, DATE_FORMATTER); }
        catch (Exception ignored) {}
        try { return LocalDate.parse(dateStr, DATE_ONLY_FORMATTER).atStartOfDay(); }
        catch (Exception ignored) {}
        return null;
    }

    // toDate → end of day (23:59:59) so the filter is inclusive
    private LocalDateTime parseToDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try { return LocalDateTime.parse(dateStr, DATE_FORMATTER); }
        catch (Exception ignored) {}
        try { return LocalDate.parse(dateStr, DATE_ONLY_FORMATTER).atTime(23, 59, 59); }
        catch (Exception ignored) {}
        return null;
    }

    // Legacy alias kept for backward compatibility (defaults to start-of-day)
    private LocalDateTime parseDate(String dateStr) {
        return parseFromDate(dateStr);
    }

    // ── Customer Overview (orders + invoices + receipts aggregated) ──────────
    public Map<String, Object> getCustomerOverview(Long customerId) {
        // Orders
        var orders = orderBookRepo.findByCustomerIdAndDeletedAtIsNull(customerId);
        // Invoices (not deleted)
        var invoices = invoiceRepository.findAll().stream()
                .filter(i -> customerId.equals(i.getCustomerId()) && i.getDeletedAt() == null)
                .collect(java.util.stream.Collectors.toList());
        // Receipts (not deleted)
        var receipts = receiptRepository.findAll().stream()
                .filter(r -> customerId.equals(r.getCustomerId()) && r.getDeletedAt() == null)
                .collect(java.util.stream.Collectors.toList());

        // Aggregate stats
        java.math.BigDecimal totalOrderValue   = orders.stream().map(o -> o.getTotalAmount()   != null ? o.getTotalAmount()   : java.math.BigDecimal.ZERO).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal totalAdvancePaid  = orders.stream().map(o -> o.getAdvanceAmount() != null ? o.getAdvanceAmount() : java.math.BigDecimal.ZERO).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal totalInvoiced     = invoices.stream().map(i -> i.getTotalAmount()  != null ? i.getTotalAmount()  : java.math.BigDecimal.ZERO).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal totalReceived     = receipts.stream().map(r -> r.getAmount()       != null ? r.getAmount()       : java.math.BigDecimal.ZERO).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        // Balance Due = Total Invoiced - Total Received (if invoices exist),
        // else Total Order Value - Total Received (covers advance-only scenarios)
        java.math.BigDecimal basis = totalInvoiced.compareTo(java.math.BigDecimal.ZERO) > 0 ? totalInvoiced : totalOrderValue;
        java.math.BigDecimal totalBalanceDue   = basis.subtract(totalReceived).max(java.math.BigDecimal.ZERO);

        long paidInvoices    = invoices.stream().filter(i -> "Paid".equalsIgnoreCase(i.getStatus())).count();
        long pendingInvoices = invoices.stream().filter(i -> !"Paid".equalsIgnoreCase(i.getStatus()) && !"Cancelled".equalsIgnoreCase(i.getStatus())).count();
        long completedOrders = orders.stream().filter(o -> "Completed".equalsIgnoreCase(o.getStatus())).count();
        long activeOrders    = orders.stream().filter(o -> !"Cancelled".equalsIgnoreCase(o.getStatus()) && !"Completed".equalsIgnoreCase(o.getStatus())).count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalOrderValue",   totalOrderValue);
        stats.put("totalBalanceDue",   totalBalanceDue);
        stats.put("totalAdvancePaid",  totalAdvancePaid);
        stats.put("totalInvoiced",     totalInvoiced);
        stats.put("totalReceived",     totalReceived);
        stats.put("paidInvoices",      paidInvoices);
        stats.put("pendingInvoices",   pendingInvoices);
        stats.put("completedOrders",   completedOrders);
        stats.put("activeOrders",      activeOrders);
        stats.put("totalOrders",       orders.size());
        stats.put("totalInvoicesCount", invoices.size());
        stats.put("totalReceiptsCount", receipts.size());

        Map<String, Object> result = new HashMap<>();
        result.put("orders",   orders);
        result.put("invoices", invoices);
        result.put("receipts", receipts);
        result.put("stats",    stats);
        return result;
    }
}