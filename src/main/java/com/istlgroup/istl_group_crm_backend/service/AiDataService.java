package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.DashboardRepo;
import com.istlgroup.istl_group_crm_backend.entity.MenuItemsEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AiDataService — all method signatures verified against actual service code.
 */
@Service
public class AiDataService {

    private static final Logger log = LoggerFactory.getLogger(AiDataService.class);

    @Autowired private LeadsService           leadsService;
    @Autowired private DashboardRepo          dashboardRepo;
    @Autowired private FollowupsService       followupsService;
    @Autowired private CustomersService       customersService;
    @Autowired private ProposalsService       proposalsService;
    @Autowired private InvoiceService         invoiceService;
    @Autowired private VendorService          vendorService;
    @Autowired private com.istlgroup.istl_group_crm_backend.repo.VendorRepository vendorRepository;
    @Autowired private TaskService            taskService;
    @Autowired private LoginService           loginService;
    @Autowired private BillService            billService;
    @Autowired private PurchaseOrderService   purchaseOrderService;
    @Autowired private OrderBookService       orderBookService;
    @Autowired private InventoryItemService   inventoryItemService;
    @Autowired private QuotationService       quotationService;
    @Autowired private ProjectExpenseService  projectExpenseService;
    @Autowired private ProjectAccessService   projectAccessService;
    @Autowired private RoleHierarchyService          roleHierarchyService;
    @Autowired private RoleMenuPermissionsService    roleMenuPermissionsService;
    @Autowired private com.istlgroup.istl_group_crm_backend.repo.ProjectRepository projectRepository;
    @Autowired private com.istlgroup.istl_group_crm_backend.repo.FollowupsRepo     followupsRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // LEADS
    // Signature: getAllLeads(Long userId, String userRole, String groupName, String subGroupName)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getLeads(Long userId, String userRole, String statusFilter, String nameSearch) {
        return getLeads(userId, userRole, statusFilter, nameSearch, "SUMMARY");
    }

    public AiDataResult getLeads(Long userId, String userRole, String statusFilter, String nameSearch, String action) {
        try {
            List<LeadWrapper> leads = leadsService.getAllLeads(userId, userRole, null, null);

            if (statusFilter != null && !statusFilter.isBlank()) {
                String sf = statusFilter.trim().toLowerCase();
                leads = leads.stream()
                    .filter(l -> l.getStatus() != null && l.getStatus().toLowerCase().contains(sf))
                    .collect(Collectors.toList());
            }

            if (nameSearch != null && !nameSearch.isBlank()) {
                String ns = normalise(nameSearch);
                String nl = nameSearch.trim().toLowerCase();
                leads = leads.stream()
                    .filter(l ->
                        (l.getName()     != null && (l.getName().toLowerCase().contains(nl) || normalise(l.getName()).contains(ns))) ||
                        (l.getLeadCode() != null && l.getLeadCode().toLowerCase().contains(nl))
                    ).collect(Collectors.toList());
            }

            if (leads.isEmpty() && nameSearch == null && statusFilter == null)
                return AiDataResult.empty("leads");

            Map<String, Object> summary = new LinkedHashMap<>();
            int lvl = roleHierarchyService.getLevelOrder(userRole);
            boolean isAdminLevel = lvl <= 2;

            if (isAdminLevel && statusFilter == null && nameSearch == null) {
                // Admin with no filter: use dashboardRepo for fast global counts
                summary.put("total_count",   dashboardRepo.countTotalLeads());
                summary.put("closed_won",    dashboardRepo.countClosedWon());
                summary.put("contacted",     dashboardRepo.countContacted());
                summary.put("in_discussion", dashboardRepo.countInDiscussion());
                summary.put("proposal_sent", dashboardRepo.countProposalSent());
            } else {
                // Non-admin OR filtered: always use the scoped 'leads' list — same data the page shows
                summary.put("total_count", leads.size());
            }

            // Filtered list counts (when status/name filter applied)
            if (statusFilter != null || nameSearch != null) {
                summary.put("filtered_count", leads.size());
            }

            Map<String, Long> byStatus = leads.stream()
                .filter(l -> l.getStatus() != null)
                .collect(Collectors.groupingBy(LeadWrapper::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            // Return record details when: searching by name, or action is LIST/DETAIL
            boolean returnRecords = (nameSearch != null && !nameSearch.isBlank())
                || "LIST".equals(action) || "DETAIL".equals(action);

            if (returnRecords) {
                // For LIST: return name + status + assigned_to (compact, readable)
                // For DETAIL (name search): return full details
                boolean fullDetail = nameSearch != null && !nameSearch.isBlank();
                List<Map<String, Object>> details = leads.stream()
                    .limit("LIST".equals(action) && nameSearch == null ? 50 : 10)
                    .map(l -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        put(m, "lead_code",   l.getLeadCode());
                        put(m, "name",        l.getName());
                        put(m, "status",      l.getStatus());
                        if (fullDetail) {
                            put(m, "phone",         l.getPhone());
                            put(m, "email",         l.getEmail());
                            put(m, "priority",      l.getPriority());
                            put(m, "source",        l.getSource());
                            put(m, "assigned_to",   l.getAssignedToName());
                            put(m, "group",         l.getGroupName());
                            put(m, "pending_followups", l.getPendingFollowupsCount());
                            put(m, "enquiry",       l.getEnquiry());
                            put(m, "created_at",    l.getCreatedAt());
                        } else {
                            put(m, "assigned_to",   l.getAssignedToName());
                            put(m, "group",         l.getGroupName());
                        }
                        return m;
                    }).collect(Collectors.toList());
                return AiDataResult.withData("leads", summary, details);
            }

            return AiDataResult.withSummary("leads", summary);

        } catch (Exception e) {
            log.error("AiDataService.getLeads: {}", e.getMessage());
            return AiDataResult.error("leads", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FOLLOW-UPS
    // Signature: getAllFollowups() / getFollowupsForUser(Long userId)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getFollowups(Long userId, String userRole, String statusFilter) {
        try {
            int level = roleHierarchyService.getLevelOrder(userRole);
            List<FollowupWrapper> followups = level <= 2
                ? followupsService.getAllFollowups()
                : followupsService.getFollowupsForUser(userId);

            if (statusFilter != null && !statusFilter.isBlank()) {
                String sf = statusFilter.trim().toLowerCase();
                if (sf.contains("overdue")) {
                    String now = java.time.LocalDateTime.now().toString();
                    followups = followups.stream()
                        .filter(f -> "Pending".equalsIgnoreCase(f.getStatus()) &&
                                     f.getScheduledAt() != null &&
                                     f.getScheduledAt().compareTo(now) < 0)
                        .collect(Collectors.toList());
                } else {
                    followups = followups.stream()
                        .filter(f -> f.getStatus() != null && f.getStatus().toLowerCase().contains(sf))
                        .collect(Collectors.toList());
                }
            }

            if (followups.isEmpty()) return AiDataResult.empty("followups");

            Map<String, Object> summary = new LinkedHashMap<>();
            // Use repo for accurate total — matches what the Follow-ups page shows
            long accurateTotal = level <= 2
                ? followupsRepo.countTotal(null, null, null)   // admin: all
                : followupsRepo.countTotal(userId, null, null); // user: own
            summary.put("total_count", accurateTotal);

            Map<String, Long> byStatus = followups.stream()
                .filter(f -> f.getStatus() != null)
                .collect(Collectors.groupingBy(FollowupWrapper::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> list = followups.stream().limit(10).map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "type",         f.getFollowupType());
                put(m, "scheduled_at", f.getScheduledAt());
                put(m, "lead_name",    f.getLeadName());
                put(m, "lead_phone",   f.getLeadPhone());
                put(m, "status",       f.getStatus());
                put(m, "notes",        f.getNotes());
                put(m, "priority",     f.getPriority());
                put(m, "assigned_to",  f.getAssignedToName());
                return m;
            }).collect(Collectors.toList());
            summary.put("records", list);

            return AiDataResult.withSummary("followups", summary);

        } catch (Exception e) {
            log.error("AiDataService.getFollowups: {}", e.getMessage());
            return AiDataResult.error("followups", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOMERS
    // Actual signature: getAllCustomersPaginated(Long userId, String userRole,
    //                       String groupName, String subGroupName, int page, int size)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getCustomers(Long userId, String userRole, String nameSearch) {
        try {
            Page<CustomerWrapper> page = customersService.getAllCustomersPaginated(
                userId, userRole, null, null, 0, 500);
            List<CustomerWrapper> customers = page.getContent();

            if (nameSearch != null && !nameSearch.isBlank()) {
                String ns = normalise(nameSearch);
                String nl = nameSearch.trim().toLowerCase();
                customers = customers.stream()
                    .filter(c ->
                        (c.getName()        != null && (c.getName().toLowerCase().contains(nl)        || normalise(c.getName()).contains(ns)))        ||
                        (c.getCompanyName() != null && (c.getCompanyName().toLowerCase().contains(nl) || normalise(c.getCompanyName()).contains(ns)))
                    ).collect(Collectors.toList());
            }

            if (customers.isEmpty()) return AiDataResult.empty("customers");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", page.getTotalElements());

            if (nameSearch != null && !nameSearch.isBlank()) {
                List<Map<String, Object>> details = customers.stream().limit(5).map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    put(m, "customer_code", c.getCustomerCode());
                    put(m, "name",          c.getName());
                    put(m, "company_name",  c.getCompanyName());
                    put(m, "phone",         c.getPhone());
                    put(m, "email",         c.getEmail());
                    put(m, "city",          c.getCity());
                    put(m, "state",         c.getState());
                    put(m, "gst_number",    c.getGstNumber());
                    put(m, "status",        c.getStatus());
                    return m;
                }).collect(Collectors.toList());
                return AiDataResult.withData("customers", summary, details);
            }

            Map<String, Long> byStatus = customers.stream()
                .filter(c -> c.getStatus() != null)
                .collect(Collectors.groupingBy(CustomerWrapper::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);
            return AiDataResult.withSummary("customers", summary);

        } catch (Exception e) {
            log.error("AiDataService.getCustomers: {}", e.getMessage());
            return AiDataResult.error("customers", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROPOSALS
    // Actual signature: getAllProposalsPaginated(Long userId, String userRole,
    //                       String groupName, String subGroupName, int page, int size)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getProposals(Long userId, String userRole, String nameSearch, String statusFilter) {
        try {
            Page<ProposalWrapper> page = proposalsService.getAllProposalsPaginated(
                userId, userRole, null, null, 0, 500);
            List<ProposalWrapper> proposals = page.getContent();

            if (nameSearch != null && !nameSearch.isBlank()) {
                String ns = normalise(nameSearch);
                String nl = nameSearch.trim().toLowerCase();
                proposals = proposals.stream()
                    .filter(p ->
                        (p.getProposalNo()   != null && p.getProposalNo().toLowerCase().contains(nl))                                                    ||
                        (p.getLeadName()     != null && (p.getLeadName().toLowerCase().contains(nl)     || normalise(p.getLeadName()).contains(ns)))     ||
                        (p.getCustomerName() != null && (p.getCustomerName().toLowerCase().contains(nl) || normalise(p.getCustomerName()).contains(ns))) ||
                        (p.getTitle()        != null && p.getTitle().toLowerCase().contains(nl))
                    ).collect(Collectors.toList());
            }

            if (statusFilter != null && !statusFilter.isBlank()) {
                String sf = statusFilter.trim().toLowerCase();
                proposals = proposals.stream()
                    .filter(p -> p.getStatus() != null && p.getStatus().toLowerCase().contains(sf))
                    .collect(Collectors.toList());
            }

            if (proposals.isEmpty()) return AiDataResult.empty("proposals");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", proposals.size());

            BigDecimal totalValue = proposals.stream()
                .filter(p -> p.getTotalValue() != null)
                .map(ProposalWrapper::getTotalValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            summary.put("total_value", totalValue);

            Map<String, Long> byStatus = proposals.stream()
                .filter(p -> p.getStatus() != null)
                .collect(Collectors.groupingBy(ProposalWrapper::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> details = proposals.stream().limit(10).map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "proposal_no",   p.getProposalNo());
                put(m, "title",         p.getTitle());
                put(m, "lead_name",     p.getLeadName());
                put(m, "customer_name", p.getCustomerName());
                put(m, "status",        p.getStatus());
                put(m, "total_value",   p.getTotalValue());
                put(m, "prepared_by",   p.getPreparedByName());
                put(m, "group",         p.getGroupName());
                put(m, "created_at",    p.getCreatedAt());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("proposals", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getProposals: {}", e.getMessage());
            return AiDataResult.error("proposals", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INVOICES
    // Actual signature: getInvoices(String groupId, String subGroupId, String projectId,
    //                       String status, String searchTerm, Long userId, String userRole,
    //                       int page, int size, String sortBy, String sortDirection,
    //                       String fromDate, String toDate)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getInvoices(Long userId, String userRole, String statusFilter) {
        return getInvoices(userId, userRole, statusFilter, null);
    }

    public AiDataResult getInvoices(Long userId, String userRole, String statusFilter, String nameSearch) {
        try {
            // Resolve: is the filter a group/category name or a real invoice status?
            GroupCategoryFilter gcf = resolveGroupCategory(statusFilter);

            Page<InvoiceEntity> page = invoiceService.getInvoices(
                gcf.groupId,               // groupId   — e.g. "EPC" or null
                gcf.subGroupId,            // subGroupId — e.g. "Solar Wind" or null
                null,                      // projectId
                gcf.statusFilter,          // status    — e.g. "Paid" or null
                nameSearch,                // searchTerm
                userId, userRole,          // userId, userRole
                0, 500,                    // page, size
                "id", "desc",             // sortBy, sortDir
                null, null                 // fromDate, toDate
            );
            List<InvoiceEntity> invoices = page.getContent();

            if (invoices.isEmpty()) return AiDataResult.empty("invoices");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", invoices.size());

            BigDecimal total       = sumBD(invoices, InvoiceEntity::getTotalAmount);
            BigDecimal paid        = sumBD(invoices, InvoiceEntity::getPaidAmount);
            BigDecimal outstanding = total.subtract(paid);
            summary.put("total_invoiced",  total);
            summary.put("total_collected", paid);
            summary.put("outstanding",     outstanding);

            // Add context about what was filtered
            if (gcf.groupId != null)    summary.put("group_filter",    gcf.groupId);
            if (gcf.subGroupId != null) summary.put("category_filter", gcf.subGroupId);

            Map<String, Long> byStatus = invoices.stream()
                .filter(i -> i.getStatus() != null)
                .collect(Collectors.groupingBy(InvoiceEntity::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> topPending = invoices.stream()
                .filter(i -> i.getBalanceAmount() != null && i.getBalanceAmount().compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> b.getBalanceAmount().compareTo(a.getBalanceAmount()))
                .limit(5)
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    put(m, "invoice_no",   i.getInvoiceNo());
                    put(m, "total_amount", i.getTotalAmount());
                    put(m, "paid_amount",  i.getPaidAmount());
                    put(m, "outstanding",  i.getBalanceAmount());
                    put(m, "status",       i.getStatus());
                    put(m, "due_date",     i.getDueDate());
                    return m;
                }).collect(Collectors.toList());
            summary.put("top_outstanding", topPending);

            return AiDataResult.withSummary("invoices", summary);

        } catch (Exception e) {
            log.error("AiDataService.getInvoices: {}", e.getMessage());
            return AiDataResult.error("invoices", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BILLS
    // Actual signature: getBills(String projectId, String groupId, String subGroupId,
    //                       String status, Long vendorId, Long poId, String searchTerm,
    //                       String billDateFromStr, String billDateToStr,
    //                       int page, int size, String sortBy, String sortDirection,
    //                       boolean isAdmin, Long userId, String userRole)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getBills(Long userId, String userRole, String statusFilter, String nameSearch) {
        try {
            boolean isAdmin = roleHierarchyService.getLevelOrder(userRole) <= 2;
            GroupCategoryFilter gcf = resolveGroupCategory(statusFilter);

            Page<BillDTO> page = billService.getBills(
                null,           // projectId
                gcf.groupId,    // groupId   — e.g. "EPC" or null
                gcf.subGroupId, // subGroupId — e.g. "Solar Wind" or null
                gcf.statusFilter,           // status
                null, null,                 // vendorId, poId
                nameSearch,                 // searchTerm
                null, null,                 // billDateFrom, billDateTo
                0, 200,                     // page, size
                "id", "desc",              // sortBy, sortDir
                isAdmin, userId, userRole   // isAdmin, userId, userRole
            );
            List<BillDTO> bills = page.getContent();
            if (bills.isEmpty()) return AiDataResult.empty("bills");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", bills.size());

            BigDecimal totalAmount = bills.stream().filter(b -> b.getTotalAmount() != null)
                .map(BillDTO::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal paidAmount  = bills.stream().filter(b -> b.getPaidAmount() != null)
                .map(BillDTO::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            summary.put("total_amount",   totalAmount);
            summary.put("paid_amount",    paidAmount);
            summary.put("pending_amount", totalAmount.subtract(paidAmount));

            if (gcf.groupId != null)    summary.put("group_filter",    gcf.groupId);
            if (gcf.subGroupId != null) summary.put("category_filter", gcf.subGroupId);

            Map<String, Long> byStatus = bills.stream()
                .filter(b -> b.getStatus() != null)
                .collect(Collectors.groupingBy(BillDTO::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> details = bills.stream().limit(10).map(b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "bill_no",      b.getBillNo());
                put(m, "vendor_name",  b.getVendorName());
                put(m, "total_amount", b.getTotalAmount());
                put(m, "paid_amount",  b.getPaidAmount());
                put(m, "outstanding",  b.getBalanceAmount());
                put(m, "status",       b.getStatus());
                put(m, "bill_date",    b.getBillDate());
                put(m, "due_date",     b.getDueDate());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("bills", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getBills: {}", e.getMessage());
            return AiDataResult.error("bills", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PURCHASE ORDERS
    // Actual signature: getPurchaseOrders(String groupName, String subGroupName,
    //                       String projectId, String status, String paymentStatus,
    //                       String searchTerm, Long userId, String userRole,
    //                       int page, int size, String sortBy, String sortDirection,
    //                       String orderDateFrom, String orderDateTo)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getPurchaseOrders(Long userId, String userRole, String statusFilter, String nameSearch) {
        try {
            GroupCategoryFilter gcf = resolveGroupCategory(statusFilter);

            Page<PurchaseOrderEntity> page = purchaseOrderService.getPurchaseOrders(
                gcf.groupId,    // groupName  — e.g. "EPC" or null
                gcf.subGroupId, // subGroupName — e.g. "Solar Wind" or null
                null,           // projectId
                gcf.statusFilter, null,      // status, paymentStatus
                nameSearch,                  // searchTerm
                null,                        // documentType (both PO + WO)
                userId, userRole,            // userId, userRole
                0, 200,                      // page, size
                "id", "desc",               // sortBy, sortDir
                null, null                   // orderDateFrom, orderDateTo
            );
            List<PurchaseOrderEntity> pos = page.getContent();
            if (pos.isEmpty()) return AiDataResult.empty("purchase_orders");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", pos.size());

            BigDecimal totalValue = pos.stream().filter(p -> p.getTotalValue() != null)
                .map(PurchaseOrderEntity::getTotalValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            summary.put("total_value", totalValue);

            if (gcf.groupId != null)    summary.put("group_filter",    gcf.groupId);
            if (gcf.subGroupId != null) summary.put("category_filter", gcf.subGroupId);

            Map<String, Long> byStatus = pos.stream()
                .filter(p -> p.getStatus() != null)
                .collect(Collectors.groupingBy(PurchaseOrderEntity::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> details = pos.stream().limit(10).map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "po_no",            p.getPoNo());
                put(m, "vendor_name",      p.getVendorName());
                put(m, "total_value",      p.getTotalValue());
                put(m, "status",           p.getStatus());
                put(m, "payment_status",   p.getPaymentStatus());
                put(m, "order_date",       p.getOrderDate());
                put(m, "expected_delivery",p.getExpectedDelivery());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("purchase_orders", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getPurchaseOrders: {}", e.getMessage());
            return AiDataResult.error("purchase_orders", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ORDER BOOK
    // Actual signature: getAllOrderBooks(int page, int size,
    //                       String groupName, String subGroupName, String projectId,
    //                       Long userId, String userRole)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getOrderBook(Long userId, String userRole, String statusFilter, String nameSearch) {
        return getOrderBook(userId, userRole, statusFilter, nameSearch, null);
    }

    public AiDataResult getOrderBook(Long userId, String userRole, String statusFilter,
                                      String nameSearch, String originalQuestion) {
        try {
            GroupCategoryFilter gcf = resolveGroupCategory(statusFilter);

            Page<OrderBookWrapper> page = orderBookService.getAllOrderBooks(
                0, 200,                      // page, size
                gcf.groupId,                 // groupName
                gcf.subGroupId,              // subGroupName
                null,                        // projectId
                userId, userRole             // userId, userRole
            );
            List<OrderBookWrapper> orders = page.getContent();

            // If it resolved to a real status (not a group/category), filter in-memory
            if (gcf.statusFilter != null && !gcf.statusFilter.isBlank()) {
                String sf = gcf.statusFilter.trim().toLowerCase();
                orders = orders.stream()
                    .filter(o -> o.getStatus() != null && o.getStatus().toLowerCase().contains(sf))
                    .collect(Collectors.toList());
            }

            // Search by name, order number, customer, or title
            String searchTerm = nameSearch;
            if ((searchTerm == null || searchTerm.isBlank()) && originalQuestion != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)(ORD-[\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(originalQuestion);
                if (m.find()) searchTerm = m.group(1);
            }

            if (searchTerm != null && !searchTerm.isBlank()) {
                String ns = normalise(searchTerm);
                String nl = searchTerm.trim().toLowerCase();
                orders = orders.stream()
                    .filter(o ->
                        (o.getOrderBookNo()  != null && o.getOrderBookNo().toLowerCase().contains(nl))  ||
                        (o.getCustomerName() != null && (o.getCustomerName().toLowerCase().contains(nl) || normalise(o.getCustomerName()).contains(ns))) ||
                        (o.getOrderTitle()   != null && o.getOrderTitle().toLowerCase().contains(nl))   ||
                        (o.getProposalNo()   != null && o.getProposalNo().toLowerCase().contains(nl))
                    ).collect(Collectors.toList());
            }

            if (orders.isEmpty()) return AiDataResult.empty("order_book");

            Map<String, Object> summary = new LinkedHashMap<>();
            // Use dashboardRepo for accurate global totals — same as dashboard KPI cards
            long totalCount       = dashboardRepo.countTotalOrders();
            java.math.BigDecimal totalValue = dashboardRepo.sumOrderBookValue();
            summary.put("total_count",  totalCount);
            summary.put("total_value",  totalValue);

            // If filtered, show filtered counts
            boolean isFiltered = gcf.isGroupOrCategory() ||
                (gcf.statusFilter != null && !gcf.statusFilter.isBlank()) ||
                (searchTerm != null && !searchTerm.isBlank());
            if (isFiltered) {
                summary.put("filtered_count", orders.size());
                BigDecimal filteredValue = orders.stream()
                    .filter(o -> o.getTotalAmount() != null)
                    .map(OrderBookWrapper::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                summary.put("filtered_value", filteredValue);
                if (gcf.groupId != null)    summary.put("group_filter",    gcf.groupId);
                if (gcf.subGroupId != null) summary.put("category_filter", gcf.subGroupId);
            }

            Map<String, Long> byStatus = orders.stream()
                .filter(o -> o.getStatus() != null)
                .collect(Collectors.groupingBy(OrderBookWrapper::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> details = orders.stream().limit(10).map(o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "order_book_no",   o.getOrderBookNo());
                put(m, "customer_name",   o.getCustomerName());
                put(m, "order_title",     o.getOrderTitle());
                put(m, "status",          o.getStatus());
                put(m, "order_date",      o.getOrderDate());
                put(m, "po_number",       o.getPoNumber());
                put(m, "po_date",         o.getPoDate());
                put(m, "subtotal",        o.getSubtotal());
                put(m, "tax_amount",      o.getTaxAmount());
                put(m, "total_amount",    o.getTotalAmount());
                put(m, "advance_amount",  o.getAdvanceAmount());
                put(m, "balance_amount",  o.getBalanceAmount());
                put(m, "group",           o.getGroupName());
                put(m, "sub_group",       o.getSubGroupName());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("order_book", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getOrderBook: {}", e.getMessage());
            return AiDataResult.error("order_book", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QUOTATIONS
    // Actual signature: getQuotations(String groupName, String subGroupName,
    //                       String projectId, String status, String searchTerm,
    //                       String uploadedFromStr, String uploadedToStr,
    //                       Long userId, String userRole,
    //                       int page, int size, String sortBy, String sortDirection)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getQuotations(Long userId, String userRole, String statusFilter, String nameSearch) {
        try {
            GroupCategoryFilter gcf = resolveGroupCategory(statusFilter);

            Page<QuotationEntity> page = quotationService.getQuotations(
                gcf.groupId,    // groupName
                gcf.subGroupId, // subGroupName
                null,           // projectId
                gcf.statusFilter,           // status
                nameSearch,                 // searchTerm
                null, null,                 // uploadedFrom, uploadedTo
                userId, userRole,           // userId, userRole
                0, 200,                     // page, size
                "id", "desc"               // sortBy, sortDir
            );
            List<QuotationEntity> quotations = page.getContent();
            if (quotations.isEmpty()) return AiDataResult.empty("quotations");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", quotations.size());

            if (gcf.groupId != null)    summary.put("group_filter",    gcf.groupId);
            if (gcf.subGroupId != null) summary.put("category_filter", gcf.subGroupId);

            Map<String, Long> byStatus = quotations.stream()
                .filter(q -> q.getStatus() != null)
                .collect(Collectors.groupingBy(QuotationEntity::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            BigDecimal totalValue = quotations.stream().filter(q -> q.getTotalValue() != null)
                .map(QuotationEntity::getTotalValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            summary.put("total_value", totalValue);

            List<Map<String, Object>> details = quotations.stream().limit(10).map(q -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "quote_no",    q.getQuoteNo());
                put(m, "vendor_name", q.getVendorName());
                put(m, "type",        q.getType());
                put(m, "status",      q.getStatus());
                put(m, "total_value", q.getTotalValue());
                put(m, "valid_till",  q.getValidTill());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("quotations", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getQuotations: {}", e.getMessage());
            return AiDataResult.error("quotations", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VENDORS
    // Actual signature: getVendors(String groupName, String subGroupName,
    //                       String projectId, String category, String vendorType,
    //                       Integer rating, String status, String searchTerm,
    //                       String createdAtFromStr, String createdAtToStr,
    //                       Long userId, String userRole,
    //                       int page, int size, String sortBy, String sortDirection)
    // VendorEntity.kycVerified is Boolean (not Integer)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getVendors(Long userId, String userRole, String nameSearch) {
        try {
            // Use findAllActiveVendors (deletedAt IS NULL) for accurate count matching the page
            org.springframework.data.domain.PageRequest pr =
                org.springframework.data.domain.PageRequest.of(0, 500);
            List<VendorEntity> vendors = vendorRepository.findAllActiveVendors(pr).getContent();

            // Apply name search filter in Java
            if (nameSearch != null && !nameSearch.isBlank()) {
                String ns = normalise(nameSearch);
                String nl = nameSearch.trim().toLowerCase();
                vendors = vendors.stream()
                    .filter(v -> v.getName() != null && (
                        v.getName().toLowerCase().contains(nl) ||
                        normalise(v.getName()).contains(ns)
                    )).collect(Collectors.toList());
            }

            if (vendors.isEmpty()) return AiDataResult.empty("vendors");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", vendors.size());

            long verified = vendors.stream()
                .filter(v -> Boolean.TRUE.equals(v.getKycVerified())).count();
            summary.put("kyc_verified", verified);
            summary.put("kyc_pending",  vendors.size() - verified);

            if (nameSearch != null && !nameSearch.isBlank()) {
                List<Map<String, Object>> details = vendors.stream().limit(5).map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    put(m, "vendor_name", v.getName());
                    put(m, "phone",       v.getPhone());
                    put(m, "email",       v.getEmail());
                    put(m, "city",        v.getCity());
                    put(m, "gst_number",  v.getGstNumber());
                    put(m, "kyc_verified",Boolean.TRUE.equals(v.getKycVerified()) ? "Verified" : "Pending");
                    put(m, "status",      v.getStatus());
                    return m;
                }).collect(Collectors.toList());
                return AiDataResult.withData("vendors", summary, details);
            }

            return AiDataResult.withSummary("vendors", summary);

        } catch (Exception e) {
            log.error("AiDataService.getVendors: {}", e.getMessage());
            return AiDataResult.error("vendors", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INVENTORY
    // Actual signature: listPaged(Long warehouseId, String groupName, String subGroupName,
    //                       String category, String search,
    //                       int page, int size, boolean includeInactive)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getInventory(Long userId, String userRole, String nameSearch) {
        try {
            Map<String, Object> result = inventoryItemService.listPaged(
                null, null, null,           // warehouseId, groupName, subGroupName
                null, nameSearch,           // category, search
                0, 200,                     // page, size
                true                        // includeInactive (show all)
            );

            @SuppressWarnings("unchecked")
            List<InventoryItemWrapper> items = (List<InventoryItemWrapper>) result.get("items");
            if (items == null) items = List.of();
            if (items.isEmpty()) return AiDataResult.empty("inventory");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", items.size());

            List<Map<String, Object>> lowStock = items.stream()
                .filter(i -> i.getCurrentQty() != null && i.getMinQty() != null &&
                             i.getCurrentQty().compareTo(i.getMinQty()) <= 0)
                .limit(10)
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    put(m, "item_code",   i.getItemCode());
                    put(m, "name",        i.getName());
                    put(m, "current_qty", i.getCurrentQty());
                    put(m, "min_qty",     i.getMinQty());
                    put(m, "unit",        i.getUnit());
                    put(m, "warehouse",   i.getLocation());
                    return m;
                }).collect(Collectors.toList());
            summary.put("low_stock_items", lowStock);
            summary.put("low_stock_count", lowStock.size());

            if (nameSearch != null && !nameSearch.isBlank()) {
                List<Map<String, Object>> details = items.stream().limit(10).map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    put(m, "item_code",   i.getItemCode());
                    put(m, "name",        i.getName());
                    put(m, "category",    i.getCategory());
                    put(m, "current_qty", i.getCurrentQty());
                    put(m, "unit",        i.getUnit());
                    put(m, "unit_cost",   i.getUnitCost());
                    put(m, "warehouse",   i.getLocation());
                    return m;
                }).collect(Collectors.toList());
                return AiDataResult.withData("inventory", summary, details);
            }

            return AiDataResult.withSummary("inventory", summary);

        } catch (Exception e) {
            log.error("AiDataService.getInventory: {}", e.getMessage());
            return AiDataResult.error("inventory", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TASKS
    // Actual signature: getTasks(Long userId, String userRole,
    //                       String search, String status, String priority, String category,
    //                       LocalDate dateFrom, LocalDate dateTo,
    //                       int page, int size, Sort sort)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getTasks(Long userId, String userRole, String statusFilter) {
        try {
            Map<String, Object> taskData = taskService.getTasks(
                userId, userRole,           // userId, userRole
                null, statusFilter,         // search, status
                null, null,                 // priority, category
                null, null,                 // dateFrom, dateTo
                1, 200,                     // page (1-based in TaskService), size
                null                        // sort
            );

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) taskData.get("tasks");
            if (tasks == null) tasks = List.of();
            if (tasks.isEmpty()) return AiDataResult.empty("tasks");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", tasks.size());

            Map<Object, Long> byStatus = tasks.stream()
                .filter(t -> t.get("status") != null)
                .collect(Collectors.groupingBy(t -> t.get("status"), Collectors.counting()));
            summary.put("by_status", byStatus);

            Map<Object, Long> byPriority = tasks.stream()
                .filter(t -> t.get("priority") != null)
                .collect(Collectors.groupingBy(t -> t.get("priority"), Collectors.counting()));
            summary.put("by_priority", byPriority);

            List<Map<String, Object>> list = tasks.stream().limit(10).map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "title",    t.get("title"));
                put(m, "status",   t.get("status"));
                put(m, "priority", t.get("priority"));
                put(m, "due_date", t.get("dueDate"));
                return m;
            }).collect(Collectors.toList());
            summary.put("tasks", list);

            return AiDataResult.withSummary("tasks", summary);

        } catch (Exception e) {
            log.error("AiDataService.getTasks: {}", e.getMessage());
            return AiDataResult.error("tasks", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROJECT EXPENSES
    // Signature: applyVisibilityToFilter(ExpenseFilterRequest, Long, String)
    //            getExpenses(ExpenseFilterRequest)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getExpenses(Long userId, String userRole, String statusFilter) {
        try {
            ExpenseFilterRequest filter = new ExpenseFilterRequest();
            filter.setStatus(statusFilter);
            filter.setPage(0);
            filter.setSize(200);
            projectExpenseService.applyVisibilityToFilter(filter, userId, userRole);

            Page<ProjectExpenseResponse> page = projectExpenseService.getExpenses(filter);
            List<ProjectExpenseResponse> expenses = page.getContent();
            if (expenses.isEmpty()) return AiDataResult.empty("expenses");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", expenses.size());

            Map<String, Long> byStatus = expenses.stream()
                .filter(e -> e.getStatus() != null)
                .collect(Collectors.groupingBy(ProjectExpenseResponse::getStatus, Collectors.counting()));
            summary.put("by_status", byStatus);

            List<Map<String, Object>> details = expenses.stream().limit(10).map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "expense_code", e.getExpenseCode());
                put(m, "project_id",   e.getProjectId());
                put(m, "trip_reason",  e.getTripReason());
                put(m, "paid_by",      e.getPaidByName());
                put(m, "status",       e.getStatus());
                put(m, "total_amount", e.getTotalAmount());
                put(m, "trip_date",    e.getTripDate());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("expenses", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getExpenses: {}", e.getMessage());
            return AiDataResult.error("expenses", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USERS
    // Signature: Users(Long userId, int page, int size) → UsersResponseWrapper
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getUsers(Long userId, String userRole) {
        try {
            UsersResponseWrapper wrapper = loginService.Users(userId, 1, 500);
            if (wrapper == null || wrapper.getUserWrapper() == null) return AiDataResult.empty("users");

            List<UserWrapper> users = wrapper.getUserWrapper();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", users.size());

            Map<String, Long> byRole = users.stream()
                .filter(u -> u.getRole() != null)
                .collect(Collectors.groupingBy(UserWrapper::getRole, Collectors.counting()));
            summary.put("by_role", byRole);

            long active = users.stream()
                .filter(u -> u.getIs_active() != null && u.getIs_active() == 1).count();
            summary.put("active_count",   active);
            summary.put("inactive_count", users.size() - active);

            return AiDataResult.withSummary("users", summary);

        } catch (Exception e) {
            log.error("AiDataService.getUsers: {}", e.getMessage());
            return AiDataResult.error("users", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROJECTS
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Checks whether a given filter string is a real ProjectStatus enum value.
     * Returns null if it's not a status (meaning it's likely a group/category name).
     */
    private com.istlgroup.istl_group_crm_backend.entity.ProjectEntity.ProjectStatus parseProjectStatus(String filter) {
        if (filter == null || filter.isBlank()) return null;
        String sf = filter.trim().toUpperCase().replace(" ", "_");
        try {
            return com.istlgroup.istl_group_crm_backend.entity.ProjectEntity.ProjectStatus.valueOf(sf);
        } catch (IllegalArgumentException e) {
            return null; // not a status — treat as group/category name
        }
    }

    public AiDataResult getProjects(Long userId, String userRole, String statusFilter, String nameSearch) {
        try {
            int level = roleHierarchyService.getLevelOrder(userRole);
            List<com.istlgroup.istl_group_crm_backend.entity.ProjectEntity> projects;

            if (level <= 2) {
                projects = projectRepository.findAll();
            } else {
                // Non-admin: only projects accessible via project_access
                List<String> projectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
                projects = projectIds.isEmpty()
                    ? List.of()
                    : projectRepository.findByProjectUniqueIdIn(projectIds);
            }

            // ── Filter logic ──────────────────────────────────────────────────
            // statusFilter can be either:
            //   (a) a real ProjectStatus enum (NOT_STARTED, PLANNING, IN_PROGRESS, COMPLETED, ON_HOLD, CANCELLED)
            //   (b) a group name like "EPC", "IoT"
            //   (c) a category/sub-group name like "Solar Wind", "Solar_Rooftop", "CCMS"
            // We try to parse as a status first; if that fails, we treat it as a
            // group/category name and filter against groupId or subGroupName.
            if (statusFilter != null && !statusFilter.isBlank()) {
                com.istlgroup.istl_group_crm_backend.entity.ProjectEntity.ProjectStatus parsedStatus =
                    parseProjectStatus(statusFilter);

                if (parsedStatus != null) {
                    // It IS a valid status — filter by status as before
                    projects = projects.stream()
                        .filter(p -> p.getStatus() == parsedStatus)
                        .collect(Collectors.toList());
                } else {
                    // NOT a status — treat as group or category/sub-group name
                    String sf = statusFilter.trim().toLowerCase().replace("_", " ");
                    projects = projects.stream()
                        .filter(p -> {
                            boolean matchesGroup = p.getGroupId() != null &&
                                p.getGroupId().toLowerCase().contains(sf);
                            boolean matchesSubGroup = p.getSubGroupName() != null &&
                                p.getSubGroupName().toLowerCase().replace("_", " ").contains(sf);
                            return matchesGroup || matchesSubGroup;
                        })
                        .collect(Collectors.toList());
                }
            }

            // Filter by name / project code
            if (nameSearch != null && !nameSearch.isBlank()) {
                String nl = nameSearch.trim().toLowerCase();
                String ns = normalise(nameSearch);
                projects = projects.stream()
                    .filter(p -> p.getProjectName() != null && (
                        p.getProjectName().toLowerCase().contains(nl) ||
                        normalise(p.getProjectName()).contains(ns) ||
                        (p.getProjectUniqueId() != null && p.getProjectUniqueId().toLowerCase().contains(nl))
                    )).collect(Collectors.toList());
            }

            if (projects.isEmpty()) return AiDataResult.empty("projects");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", projects.size());

            // Financial aggregates — summed from pre-computed project stats columns
            java.math.BigDecimal totalBudget   = projects.stream()
                .filter(p -> p.getBudget() != null)
                .map(com.istlgroup.istl_group_crm_backend.entity.ProjectEntity::getBudget)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal totalInvoiced = projects.stream()
                .filter(p -> p.getTotalInvoiceValue() != null)
                .map(com.istlgroup.istl_group_crm_backend.entity.ProjectEntity::getTotalInvoiceValue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal totalReceived = projects.stream()
                .filter(p -> p.getPaidInvoiceValue() != null)
                .map(com.istlgroup.istl_group_crm_backend.entity.ProjectEntity::getPaidInvoiceValue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal totalProcurement = projects.stream()
                .filter(p -> p.getTotalBillValue() != null)
                .map(com.istlgroup.istl_group_crm_backend.entity.ProjectEntity::getTotalBillValue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal pendingPayments = projects.stream()
                .filter(p -> p.getPendingPaymentValue() != null)
                .map(com.istlgroup.istl_group_crm_backend.entity.ProjectEntity::getPendingPaymentValue)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

            if (totalBudget.compareTo(java.math.BigDecimal.ZERO) > 0)
                summary.put("total_project_value", totalBudget);
            if (totalInvoiced.compareTo(java.math.BigDecimal.ZERO) > 0)
                summary.put("total_billed", totalInvoiced);
            if (totalReceived.compareTo(java.math.BigDecimal.ZERO) > 0)
                summary.put("total_received", totalReceived);
            if (totalProcurement.compareTo(java.math.BigDecimal.ZERO) > 0)
                summary.put("total_procurement", totalProcurement);
            if (pendingPayments.compareTo(java.math.BigDecimal.ZERO) > 0)
                summary.put("pending_payments", pendingPayments);

            // Status breakdown
            Map<String, Long> byStatus = projects.stream()
                .filter(p -> p.getStatus() != null)
                .collect(Collectors.groupingBy(p -> p.getStatus().name(), Collectors.counting()));
            summary.put("by_status", byStatus);

            // Category breakdown (sub_group_name)
            Map<String, Long> byCategory = projects.stream()
                .filter(p -> p.getSubGroupName() != null)
                .collect(Collectors.groupingBy(
                    p -> p.getSubGroupName().replace("_", " "),
                    Collectors.counting()));
            if (!byCategory.isEmpty()) summary.put("by_category", byCategory);

            // Full list with names and financials
            List<Map<String, Object>> details = projects.stream().limit(50).map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "project_id",    p.getProjectUniqueId());
                put(m, "project_name",  p.getProjectName());
                put(m, "group",         p.getGroupId());
                put(m, "category",      p.getSubGroupName() != null ? p.getSubGroupName().replace("_", " ") : null);
                put(m, "status",        p.getStatus() != null ? p.getStatus().name() : null);
                put(m, "location",      p.getLocation());
                put(m, "start_date",    p.getStartDate());
                put(m, "end_date",      p.getEndDate());
                put(m, "budget",        p.getBudget());
                put(m, "total_invoiced", p.getTotalInvoiceValue());
                put(m, "total_received", p.getPaidInvoiceValue());
                put(m, "total_procurement", p.getTotalBillValue());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("projects", summary, details);

        } catch (Exception e) {
            log.error("AiDataService.getProjects: {}", e.getMessage());
            return AiDataResult.error("projects", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MENU ITEMS (Office Use — roles/permissions/menus config)
    // ─────────────────────────────────────────────────────────────────────────
    public AiDataResult getMenuItems() {
        try {
            List<MenuItemsEntity> items = roleMenuPermissionsService.getAllMenuItems();
            if (items == null || items.isEmpty()) return AiDataResult.empty("menu_items");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_count", items.size());

            List<Map<String, Object>> details = items.stream().map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                put(m, "id",   i.getId());
                put(m, "name", i.getName());
                return m;
            }).collect(Collectors.toList());

            return AiDataResult.withData("menu_items", summary, details);
        } catch (Exception e) {
            log.error("AiDataService.getMenuItems: {}", e.getMessage());
            return AiDataResult.error("menu_items", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts a CRM record code from the original question when the intent
     * classifier missed it — handles patterns like ORD-2026-000022,
     * LEAD-2025-000014, PROP-2026-0008, INV-2025-001, etc.
     */
    private String extractCodeFromQuestion(String question, String prefix) {
        if (question == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?i)(" + prefix + "[\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(question);
        return m.find() ? m.group(1) : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP / CATEGORY FILTER RESOLVER
    // Determines whether a filter string is a real status value or a
    // group/category (sub-group) name, and resolves the correct groupId and
    // subGroupId to pass to the underlying service methods.
    //
    // DB structure:
    //   invoices.group_id      = group name string, e.g. "EPC", "IoT"
    //   invoices.sub_group_id  = sub-group / category string, e.g. "Solar Wind",
    //                            "Solar_Rooftop", "CCMS", "MCMS",
    //                            "Solar_ground_mounted", "Solar_carports"
    //   bills / POs / quotations / order_book have the same pattern.
    //
    // Known groups (group_id values):
    //   "EPC"  — Engineering, Procurement & Construction
    //   "IoT"  — Internet of Things
    //
    // Known categories (sub_group_name / sub_group_id values):
    //   Solar Wind, Solar_Rooftop, Solar_carports, Solar_ground_mounted,
    //   CCMS, MCMS
    // ─────────────────────────────────────────────────────────────────────────

    private static final java.util.Set<String> KNOWN_GROUPS = new java.util.HashSet<>(
        java.util.Arrays.asList("epc", "iot")
    );

    /**
     * Result of resolving a filter string to group/category coordinates.
     * groupId    — the group_id column value (e.g. "EPC"), null if not a group filter
     * subGroupId — the sub_group_id column value (e.g. "Solar Wind"), null if not a sub-group filter
     * statusFilter — the original string if it's a real status, null otherwise
     */
    static class GroupCategoryFilter {
        final String groupId;
        final String subGroupId;
        final String statusFilter;
        GroupCategoryFilter(String groupId, String subGroupId, String statusFilter) {
            this.groupId = groupId;
            this.subGroupId = subGroupId;
            this.statusFilter = statusFilter;
        }
        boolean isGroupOrCategory() { return groupId != null || subGroupId != null; }
    }

    /**
     * Resolves a raw filter string to its correct meaning:
     *   – If it maps to a known group name ("EPC", "IoT") → groupId filter
     *   – If it maps to a known category/sub-group name   → subGroupId filter
     *   – Otherwise                                        → treat as a status filter
     *
     * Matching is case-insensitive and normalised (underscores == spaces).
     */
    GroupCategoryFilter resolveGroupCategory(String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return new GroupCategoryFilter(null, null, null);
        }
        String lower = rawFilter.trim().toLowerCase().replace("_", " ");

        // ── Group check ────────────────────────────────────────────────────
        if (lower.equals("epc"))  return new GroupCategoryFilter("EPC",  null, null);
        if (lower.equals("iot"))  return new GroupCategoryFilter("IoT",  null, null);

        // ── Category / sub-group check ─────────────────────────────────────
        // Normalise: "solar wind", "solarwind", "Solar_Wind" all map to "Solar Wind"
        if (lower.contains("solar wind")  || lower.equals("solarwind"))
            return new GroupCategoryFilter(null, "Solar Wind",  null);
        if (lower.contains("solar roof")  || lower.contains("rooftop"))
            return new GroupCategoryFilter(null, "Solar_Rooftop", null);
        if (lower.contains("ground mount") || lower.contains("ground_mount"))
            return new GroupCategoryFilter(null, "Solar_ground_mounted", null);
        if (lower.contains("carport"))
            return new GroupCategoryFilter(null, "Solar_carports", null);
        if (lower.equals("ccms"))
            return new GroupCategoryFilter(null, "CCMS", null);
        if (lower.equals("mcms"))
            return new GroupCategoryFilter(null, "MCMS", null);

        // ── Not a group/category — pass through as status ──────────────────
        return new GroupCategoryFilter(null, null, rawFilter.trim());
    }

    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[\\s.,'\"\\-()]", "")
                .replaceAll("pvtltd|pvtlimited|privatelimited|pvt|ltd|limited|inc|llc|" +
                            "energy|solutions|group|technologies|tech|enterprises|trading", "");
    }

    private void put(Map<String, Object> m, String key, Object value) {
        if (value != null && !value.toString().isBlank()) m.put(key, value);
    }

    private <T> BigDecimal sumBD(List<T> list, java.util.function.Function<T, BigDecimal> getter) {
        return list.stream().map(getter).filter(Objects::nonNull)
                   .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result wrapper
    // ─────────────────────────────────────────────────────────────────────────

    public static class AiDataResult {
        private final String module;
        private final boolean success;
        private final boolean empty;
        private final String errorMessage;
        private final Map<String, Object> summary;
        private final List<Map<String, Object>> records;

        private AiDataResult(String module, boolean success, boolean empty,
                              String errorMessage, Map<String, Object> summary,
                              List<Map<String, Object>> records) {
            this.module = module; this.success = success; this.empty = empty;
            this.errorMessage = errorMessage; this.summary = summary; this.records = records;
        }

        public static AiDataResult empty(String module) {
            return new AiDataResult(module, true, true, null, Map.of(), List.of()); }
        public static AiDataResult withSummary(String module, Map<String, Object> s) {
            return new AiDataResult(module, true, false, null, s, List.of()); }
        public static AiDataResult withData(String module, Map<String, Object> s, List<Map<String, Object>> r) {
            return new AiDataResult(module, true, false, null, s, r); }
        public static AiDataResult error(String module, String msg) {
            return new AiDataResult(module, false, false, msg, Map.of(), List.of()); }

        public String getModule()                     { return module; }
        public boolean isSuccess()                    { return success; }
        public boolean isEmpty()                      { return empty; }
        public String getErrorMessage()               { return errorMessage; }
        public Map<String, Object> getSummary()       { return summary; }
        public List<Map<String, Object>> getRecords() { return records; }
    }
}