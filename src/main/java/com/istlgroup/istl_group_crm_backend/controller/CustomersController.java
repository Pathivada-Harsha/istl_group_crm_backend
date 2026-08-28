package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.ClientFinancialsService;
import com.istlgroup.istl_group_crm_backend.service.CustomersService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
//@CrossOrigin(origins = "${cros.allowed-origins}")
public class CustomersController {
    
    private final CustomersService        customersService;
    private final ClientFinancialsService clientFinancialsService;
    
    /**
     * Get all customers
     * GET /customers/getAll?groupName=Solar&subGroupName=Residential
     */
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllCustomers(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            Page<CustomerWrapper> customerPage = customersService.getAllCustomersPaginated(
                userId, userRole, groupName, subGroupName, page, size
            );
            
            Map<String, Object> pageData = new HashMap<>();
            pageData.put("content", customerPage.getContent());
            pageData.put("currentPage", customerPage.getNumber());
            pageData.put("totalElements", customerPage.getTotalElements());
            pageData.put("totalPages", customerPage.getTotalPages());
            
            response.put("success", true);
            response.put("data", pageData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
 // ADD THIS METHOD TO YOUR CustomersController.java

    /**
     * NEW: Get customers filtered by group and subgroup for Order Book dropdown
     * Returns only id, customerCode, and name
     * GET /customers/by-group?groupName=Solar&subGroupName=Residential
     */
    @GetMapping("/by-group")
    public ResponseEntity<Map<String, Object>> getCustomersByGroup(
            @RequestParam String groupName,
            @RequestParam(required = false) String subGroupName,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            // Call service method to get simplified customer list
            List<Map<String, Object>> customers = customersService.getCustomersByGroupForDropdown(
                userId, userRole, groupName, subGroupName
            );
            
            response.put("success", true);
            response.put("data", customers);
            response.put("message", "Customers fetched successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch customers: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    /**
     * Get filtered customers
     * POST /customers/filter
     */
    @PostMapping("/filter")
    public ResponseEntity<Map<String, Object>> filterCustomers(
            @RequestBody CustomerFilterRequestWrapper filterRequest,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            int page = filterRequest.getPage() != null ? filterRequest.getPage() : 0;
            int size = filterRequest.getSize() != null ? filterRequest.getSize() : 10;
            
            Page<CustomerWrapper> customerPage = customersService.getFilteredCustomersPaginated(
                userId, userRole, filterRequest, page, size
            );
            
            Map<String, Object> pageData = new HashMap<>();
            pageData.put("content", customerPage.getContent());
            pageData.put("currentPage", customerPage.getNumber());
            pageData.put("totalElements", customerPage.getTotalElements());
            pageData.put("totalPages", customerPage.getTotalPages());
            
            response.put("success", true);
            response.put("data", pageData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Get customer by ID
     * GET /customers/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCustomerById(
            @PathVariable Long id,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            CustomerWrapper customer = customersService.getCustomerById(id, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", customer);
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * Create new customer
     * POST /customers/create
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createCustomer(
            @RequestBody CustomerRequestWrapper requestWrapper,
            @ActingUserId Long userId) {
        try {
            CustomerWrapper customer = customersService.createCustomer(requestWrapper, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", customer);
            response.put("message", "Customer created successfully");
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }
    
    /**
     * Update customer
     * PUT /customers/update/{id}
     */
    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> updateCustomer(
            @PathVariable Long id,
            @RequestBody CustomerRequestWrapper requestWrapper,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            CustomerWrapper customer = customersService.updateCustomer(id, requestWrapper, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", customer);
            response.put("message", "Customer updated successfully");
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }
    
    /**
     * Delete customer (soft delete)
     * DELETE /customers/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> deleteCustomer(
            @PathVariable Long id,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            customersService.deleteCustomer(id, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Customer deleted successfully");
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }

    /**
     * Get customer financial overview: orders + invoices + receipts aggregated
     * GET /customers/{id}/overview
     */
    @GetMapping("/{id}/overview")
    public ResponseEntity<Map<String, Object>> getCustomerOverview(
            @PathVariable Long id,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            Map<String, Object> overview = customersService.getCustomerOverview(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", overview);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Client Financials roll-up: money in / money out across every project this
     * client has, computed LIVE from invoices / receipts / bills / payments.
     *
     * Distinct from /overview above, which totals the invoices and receipts
     * carrying this customer_id directly. This one aggregates the PROJECTS, so
     * its figures are the same ones each project's own dashboard shows and the
     * two screens can be reconciled against each other line by line.
     *
     * GET /customers/{id}/financials
     */
    @GetMapping("/{id}/financials")
    public ResponseEntity<Map<String, Object>> getClientFinancials(
            @PathVariable Long id,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", clientFinancialsService.getClientFinancials(id));
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
