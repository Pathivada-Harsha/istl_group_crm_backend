package com.istlgroup.istl_group_crm_backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// Resource/UrlResource no longer needed — files served from BLOB bytes directly
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import com.istlgroup.istl_group_crm_backend.service.BillService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.service.PurchaseOrderService;
import com.istlgroup.istl_group_crm_backend.service.VendorService;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillItemDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillStatsDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PaymentDTO;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/bills")
@RequiredArgsConstructor
@Slf4j
public class BillController {

    private final BillService billService;
    private final ProjectAccessService projectAccessService;
    private final VendorRepository vendorRepository;
    private final ProjectRepository projectRepository;
    private final VendorService vendorService;
    private final PurchaseOrderService purchaseOrderService;
    private final RoleHierarchyRepo roleHierarchyRepo;
    private VendorEntity vendorEntity;
    private PurchaseOrderRepository purchaseOrderRepository;

    // =========================================================================
    // GET ALL
    // =========================================================================

    @GetMapping
    public ResponseEntity<Map<String, Object>> getBills(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long poId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String billDateFrom,
            @RequestParam(required = false) String billDateTo,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "billDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestHeader(value = "User-Role", required = false, defaultValue = "USER") String userRole,
            @RequestHeader(value = "User-Id",   required = false) Long userId,
            HttpServletRequest request
    ) {
        try {
            if (userId == null) userId = getUserIdFromRequest(request);
            if (userId != null && userId > 0
                    && projectId != null && !projectId.isBlank()
                    && !projectAccessService.canAccessProject(projectId, userId, userRole)) {
                return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "You do not have access to this project"));
            }

            boolean isAdmin = isAdminRole(userRole);

            Page<BillDTO> bills = billService.getBills(
                    projectId, groupId, subGroupId, status, vendorId, poId,
                    search, billDateFrom, billDateTo, page, size, sortBy, sortDirection, isAdmin, userId, userRole
            );

            List<String> projectIds = bills.getContent().stream()
                .map(BillDTO::getProjectId)
                .filter(pid -> pid != null && !pid.isBlank())
                .distinct()
                .collect(Collectors.toList());
            Map<String, String> projectNameMap = new java.util.HashMap<>();
            if (!projectIds.isEmpty()) {
                projectRepository.findByProjectUniqueIdIn(projectIds)
                    .forEach(p -> projectNameMap.put(p.getProjectUniqueId(), p.getProjectName()));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("bills",        bills.getContent());
            response.put("projectNames", projectNameMap);
            response.put("currentPage",  bills.getNumber());
            response.put("totalPages",   bills.getTotalPages());
            response.put("totalItems",   bills.getTotalElements());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching bills", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // =========================================================================
    // OUTSTANDINGS — dedicated endpoint for BillsOutstandingsTab
    // Returns all bills + outstanding-only subset in a single call.
    // No pagination; no nullable-column sort; no date-range constraints.
    // =========================================================================

    @GetMapping("/outstandings")
    public ResponseEntity<Map<String, Object>> getOutstandings(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestHeader(value = "User-Role", required = false, defaultValue = "USER") String userRole,
            @RequestHeader(value = "User-Id",   required = false) Long userId,
            HttpServletRequest request
    ) {
        try {
            if (userId == null) userId = getUserIdFromRequest(request);
            if (userRole == null || userRole.isBlank()) userRole = getUserRoleFromRequest(request);

            if (userId != null && userId > 0
                    && projectId != null && !projectId.isBlank()
                    && !projectAccessService.canAccessProject(projectId, userId, userRole)) {
                return ResponseEntity.status(403)
                        .body(Map.of("success", false, "message", "You do not have access to this project"));
            }

            boolean isAdmin = isAdminRole(userRole);

            Map<String, Object> data = billService.getOutstandings(
                    projectId, groupId, subGroupId, isAdmin, userId, userRole);

            return ResponseEntity.ok(data);

        } catch (Exception e) {
            log.error("Error fetching outstandings", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // GET BY ID
    // =========================================================================

    @GetMapping("/{id}")
    public ResponseEntity<?> getBillById(@PathVariable Long id) {
        try {
            BillDTO bill = billService.getBillById(id);
            return ResponseEntity.ok(bill);
        } catch (RuntimeException e) {
            log.error("Error fetching bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error fetching bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    @PostMapping
    public ResponseEntity<?> createBill(
            @RequestBody Map<String, Object> billRequest,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            log.info("Creating bill - User: {}, Request keys: {}", userId, billRequest.keySet());

            Object vendorIdObj = billRequest.get("vendorId");
            if (vendorIdObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Vendor ID is required"));
            }

            Long   actualVendorId = null;
            String vendorIdStr    = vendorIdObj.toString().trim();

            if (vendorIdStr.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Vendor ID cannot be empty"));
            }

            if (vendorIdStr.startsWith("PO_")) {
                String vendorName = vendorIdStr.substring(3).trim();
                if (vendorName.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid PO vendor format"));
                }

                VendorEntity vendor = vendorRepository.findByName(vendorName)
                        .orElseGet(() -> {
                            VendorEntity nv = new VendorEntity();
                            nv.setName(vendorName);

                            Object c = billRequest.get("vendorContact");
                            if (c != null && !c.toString().trim().isEmpty()) nv.setPhone(c.toString().trim());

                            Object g = billRequest.get("groupId");
                            if (g != null && !g.toString().trim().isEmpty()) nv.setGroupName(g.toString().trim());

                            Object sg = billRequest.get("subGroupId");
                            if (sg != null && !sg.toString().trim().isEmpty()) nv.setSubGroupName(sg.toString().trim());

                            Object p = billRequest.get("projectId");
                            if (p != null && !p.toString().trim().isEmpty()) nv.setProjectId(p.toString().trim());

                            nv.setStatus("Active");
                            nv.setTotalOrders(0);
                            nv.setTotalPurchaseValue(BigDecimal.ZERO);
                            nv.setCreatedBy(userId);
                            vendorEntity = vendorRepository.save(nv);

                            purchaseOrderRepository.updateVendorIdForPO(
                                    vendorEntity.getId(), vendorEntity.getName(),
                                    vendorEntity.getPhone(), vendorEntity.getProjectId());
                            return vendorEntity;
                        });

                actualVendorId = vendor.getId();

            } else {
                try {
                    actualVendorId = Long.parseLong(vendorIdStr);
                    if (!vendorRepository.existsById(actualVendorId)) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Vendor not found with ID: " + actualVendorId));
                    }
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid vendor ID format: " + vendorIdStr));
                }
            }

            Long   poId    = null;
            Object poIdObj = billRequest.get("poId");
            if (poIdObj != null && !poIdObj.toString().trim().isEmpty()) {
                try { poId = Long.parseLong(poIdObj.toString().trim()); }
                catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid PO ID format"));
                }
            }

            Object    billDateObj = billRequest.get("billDate");
            if (billDateObj == null || billDateObj.toString().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Bill date is required"));
            }

            Object    dueDateObj = billRequest.get("dueDate");
            LocalDate billDate   = null;
            LocalDate dueDate    = null;
            try {
                billDate = LocalDate.parse(billDateObj.toString().trim());
                if (dueDateObj != null && !dueDateObj.toString().trim().isEmpty()) {
                    dueDate = LocalDate.parse(dueDateObj.toString().trim());
                }
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format. Use YYYY-MM-DD"));
            }

            String projectId  = billRequest.get("projectId")  != null ? billRequest.get("projectId").toString().trim()  : null;
            String groupId    = billRequest.get("groupId")    != null ? billRequest.get("groupId").toString().trim()    : null;
            String subGroupId = billRequest.get("subGroupId") != null ? billRequest.get("subGroupId").toString().trim() : null;
            String notes      = billRequest.get("notes")      != null ? billRequest.get("notes").toString().trim()      : null;
            String billNo     = billRequest.get("billNo")     != null ? billRequest.get("billNo").toString().trim()     : null;
            String billRefId  = billRequest.get("billRefId")  != null ? billRequest.get("billRefId").toString().trim()  : null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemsData = (List<Map<String, Object>>) billRequest.get("items");
            if (itemsData == null || itemsData.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "At least one item is required"));
            }

            List<BillItemDTO> items;
            try {
                items = itemsData.stream().map(itemMap -> {
                    BillItemDTO item = new BillItemDTO();
                    Object pid = itemMap.get("poItemId");
                    if (pid != null && !pid.toString().trim().isEmpty()) {
                        try { item.setPoItemId(Long.parseLong(pid.toString().trim())); }
                        catch (NumberFormatException e) { throw new RuntimeException("Invalid PO Item ID: " + pid); }
                    }
                    Object qty = itemMap.get("quantity");
                    if (qty == null) throw new RuntimeException("Quantity is required for all items");
                    BigDecimal q = new BigDecimal(qty.toString());
                    if (q.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("Quantity must be greater than zero");
                    item.setQuantity(q);
                    Object n  = itemMap.get("itemName");    if (n  != null) item.setItemName(n.toString());
                    Object d  = itemMap.get("description"); if (d  != null) item.setDescription(d.toString());
                    Object up = itemMap.get("unitPrice");   if (up != null) item.setUnitPrice(new BigDecimal(up.toString()));
                    Object tp = itemMap.get("taxPercent");  if (tp != null) item.setTaxPercent(new BigDecimal(tp.toString()));
                    return item;
                }).collect(Collectors.toList());
            } catch (RuntimeException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid item data: " + e.getMessage()));
            }

            BillDTO dto = new BillDTO();
            dto.setBillNo(billNo);
            dto.setBillRefId(billRefId);
            dto.setVendorId(actualVendorId);
            dto.setPoId(poId);
            dto.setBillDate(billDate);
            dto.setDueDate(dueDate);
            dto.setProjectId(projectId);
            dto.setGroupId(groupId);
            dto.setSubGroupId(subGroupId);
            dto.setNotes(notes);
            dto.setItems(items);

            BillDTO createdBill = billService.createBill(dto, userId);
            log.info("Bill created successfully: {}", createdBill.getBillNo());
            return ResponseEntity.status(HttpStatus.CREATED).body(createdBill);

        } catch (RuntimeException e) {
            log.error("Runtime error creating bill", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating bill", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    @PutMapping("/{id}")
    public ResponseEntity<?> updateBill(
            @PathVariable Long id,
            @RequestBody BillDTO billDTO,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO updatedBill = billService.updateBill(id, billDTO, userId);
            return ResponseEntity.ok(updatedBill);
        } catch (RuntimeException e) {
            log.error("Error updating bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBill(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            billService.deleteBill(id, userId);
            return ResponseEntity.ok(Map.of("message", "Bill deleted successfully"));
        } catch (RuntimeException e) {
            log.error("Error deleting bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
        }
    }

    // =========================================================================
    // FILE UPLOAD / DOWNLOAD / VIEW
    // =========================================================================

    @PostMapping("/{id}/upload")
    public ResponseEntity<?> uploadBillFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            String filePath = billService.uploadBillFile(id, file, userId);
            return ResponseEntity.ok(Map.of("message", "File uploaded successfully", "filePath", filePath));
        } catch (RuntimeException e) {
            log.error("Error uploading file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Error uploading file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "File upload failed"));
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadBillFile(@PathVariable Long id) {
        try {
            BillDTO bill = billService.getBillById(id);
            if (bill.getBillFileName() == null && bill.getBillFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = billService.getBillFileBytes(id);
            String contentType = billService.getBillFileContentType(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + bill.getBillFileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                    .body(bytes);
        } catch (Exception e) {
            log.error("Error downloading file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<byte[]> viewBillFile(@PathVariable Long id) {
        try {
            BillDTO bill = billService.getBillById(id);
            if (bill.getBillFileName() == null && bill.getBillFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = billService.getBillFileBytes(id);
            String contentType = billService.getBillFileContentType(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + bill.getBillFileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                    .body(bytes);
        } catch (Exception e) {
            log.error("Error viewing file for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // =========================================================================
    // PAYMENTS
    // =========================================================================

    @PostMapping("/{id}/payments")
    public ResponseEntity<?> addPayment(
            @PathVariable Long id,
            @RequestBody PaymentDTO paymentDTO,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO updatedBill = billService.addPayment(id, paymentDTO, userId);
            return ResponseEntity.ok(updatedBill);
        } catch (RuntimeException e) {
            log.error("Error adding payment to bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error adding payment to bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
        }
    }

    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<?> markAsPaid(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            BillDTO updatedBill = billService.markAsPaid(id, userId);
            return ResponseEntity.ok(updatedBill);
        } catch (RuntimeException e) {
            log.error("Error marking bill as paid: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error marking bill as paid: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
        }
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String billDateFrom,
            @RequestParam(required = false) String billDateTo,
            HttpServletRequest request
    ) {
        try {
            Long   userId   = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);

            BillStatsDTO stats = billService.getStatistics(
                    projectId, groupId, subGroupId, userId, userRole, status, search, billDateFrom, billDateTo
            );
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching bill statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    // =========================================================================
    // DEDICATED MODAL ENDPOINTS — Bills Management Create/Edit Modal
    //
    // These endpoints are used ONLY by the BillsManagementPage modal dropdowns.
    // They delegate to the same underlying service logic but through isolated
    // paths, so changes to /vendors/for-bills or /purchase-orders/by-vendor
    // will NEVER affect the bill modal, and vice-versa.
    // =========================================================================

    /**
     * GET /api/bills/modal/vendors
     *
     * Returns vendors for the Bills Create/Edit modal vendor dropdown.
     * Includes both registered vendors and PO-linked vendors under the given
     * project/group scope. Dedicated endpoint — isolated from all other pages.
     *
     * Query params (all optional):
     *   projectId    — project unique-ID string
     *   groupName    — group name
     *   subGroupName — sub-group name
     */
    @GetMapping("/modal/vendors")
    public ResponseEntity<?> getVendorsForBillModal(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName) {
        try {
            // Delegates to VendorService.getVendorsForBills which already handles
            // PO-linked vendor discovery and deduplication.
            List<Map<String, Object>> vendors = vendorService.getVendorsForBills(
                    groupName, subGroupName, projectId);
            return ResponseEntity.ok(vendors);
        } catch (Exception e) {
            log.error("Error fetching vendors for bill modal", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/bills/modal/purchase-orders
     *
     * Returns Purchase Orders for the Bills Create/Edit modal PO dropdown,
     * filtered by vendor and project/group scope.
     * Dedicated endpoint — isolated from GET /purchase-orders/by-vendor.
     *
     * Query params:
     *   vendorId     (optional) — numeric vendor ID
     *   vendorName   (optional) — vendor name string (legacy PO-only vendors)
     *   projectId    (optional) — project unique-ID string
     *   groupName    (optional) — group name
     *   subGroupName (optional) — sub-group name
     */
    @GetMapping("/modal/purchase-orders")
    public ResponseEntity<?> getPurchaseOrdersForBillModal(
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String vendorName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName) {
        try {
            // Delegates to PurchaseOrderService which handles both vendorId + vendorName lookups.
            List<Map<String, Object>> pos = purchaseOrderService.getPurchaseOrdersByVendor1(
                    vendorId, vendorName, groupName, subGroupName, projectId);
            return ResponseEntity.ok(pos);
        } catch (Exception e) {
            log.error("Error fetching purchase orders for bill modal", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Object attr = request.getAttribute("userId");
        if (attr instanceof Long)    return (Long) attr;
        if (attr instanceof Integer) return ((Integer) attr).longValue();
        if (attr instanceof String)  return Long.parseLong((String) attr);

        String header = request.getHeader("X-User-Id");
        if (header != null) {
            try { return Long.parseLong(header); }
            catch (NumberFormatException e) { log.warn("Invalid userId in header: {}", header); }
        }
        return 1L;
    }

    private String getUserRoleFromRequest(HttpServletRequest request) {
        Object attr = request.getAttribute("userRole");
        if (attr != null) return attr.toString();

        String header = request.getHeader("X-User-Role");
        if (header != null) return header;

        return "USER";
    }

    /**
     * Returns true when the user should see ALL data without project-access filtering.
     * Rules:
     *   1. ADMIN / SUPERADMIN always bypass.
     *   2. Any role whose name starts with ACCOUNTS_ bypasses (covers ACCOUNTS_EXECUTIVE,
     *      ACCOUNTS_MANAGER, ACCOUNTS_CFO, or any future ACCOUNTS_* role).
     *   3. Any role whose level_order in role_hierarchy is <= 2 bypasses
     *      (top-level roles configured dynamically in the DB).
     */
    private boolean isAdminRole(String userRole) {
        if (userRole == null) return false;
        String r = userRole.trim().toUpperCase();
        if (r.equals("ADMIN") || r.equals("SUPERADMIN")) return true;
        if (r.startsWith("ACCOUNTS_")) return true;
        return roleHierarchyRepo.findLevelOrderByRoleName(RoleNormalizer.normalize(userRole))
                .map(level -> level <= 2)
                .orElse(false);
    }
}