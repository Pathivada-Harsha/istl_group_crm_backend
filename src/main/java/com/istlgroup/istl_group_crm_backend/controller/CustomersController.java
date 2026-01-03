package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
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
@CrossOrigin(origins = "${cros.allowed-origins}")
public class CustomersController {
    
    private final CustomersService customersService;
    
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
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
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
    
    /**
     * Get filtered customers
     * POST /customers/filter
     */
    @PostMapping("/filter")
    public ResponseEntity<Map<String, Object>> filterCustomers(
            @RequestBody CustomerFilterRequestWrapper filterRequest,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
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
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
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
            @RequestHeader("User-Id") Long userId) {
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
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
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
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
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
}