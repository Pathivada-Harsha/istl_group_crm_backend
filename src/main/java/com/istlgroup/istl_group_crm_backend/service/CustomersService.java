package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
@Service
public class CustomersService {
    
    @Autowired
    private CustomersRepo customersRepo;
    
    @Autowired
    private UsersRepo usersRepo;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * Get all customers based on user role
     */
    public List<CustomerWrapper> getAllCustomers(Long userId, String userRole, String groupName, String subGroupName) {
        List<CustomersEntity> customers;
        
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            // Admin sees all customers
            customers = customersRepo.findByDeletedAtIsNull();
        } else {
            // Regular users see only customers created by them
            customers = customersRepo.findByCreatedByAndDeletedAtIsNull(userId);
        }
        
        // Apply group filter if provided
        if (groupName != null && !groupName.isEmpty()) {
            try {
                CustomersEntity.GroupName group = CustomersEntity.GroupName.valueOf(groupName);
                customers = customers.stream()
                    .filter(c -> c.getGroupName() == group)
                    .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {
                // Invalid group name, return empty list
                return List.of();
            }
        }
        
        return customers.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }
    
    /**
     * Get filtered customers based on role
     */
    public List<CustomerWrapper> getFilteredCustomers(Long userId, String userRole, CustomerFilterRequestWrapper filterRequest) {
        List<CustomersEntity> customers;
        
        // Parse enum values
        CustomersEntity.GroupName groupName = parseGroupName(filterRequest.getGroupName());
        CustomersEntity.CustomerStatus status = parseStatus(filterRequest.getStatus());
        LocalDateTime fromDate = parseDate(filterRequest.getFromDate());
        LocalDateTime toDate = parseDate(filterRequest.getToDate());
        
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            customers = customersRepo.searchCustomers(
                filterRequest.getSearchTerm(),
                groupName,
                status,
                filterRequest.getCity(),
                filterRequest.getState(),
                filterRequest.getAssignedTo(),
                fromDate,
                toDate
            );
        } else {
            customers = customersRepo.searchCustomersForUser(
                userId,
                filterRequest.getSearchTerm(),
                groupName,
                status,
                filterRequest.getCity(),
                filterRequest.getState(),
                fromDate,
                toDate
            );
        }
        
        return customers.stream()
                .map(this::convertToWrapper)
                .collect(Collectors.toList());
    }
    
    /**
     * Get customer by ID with role-based access control
     */
    public CustomerWrapper getCustomerById(Long customerId, Long userId, String userRole) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
                .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));
        
        if (customer.getDeletedAt() != null) {
            throw new CustomException("Customer has been deleted");
        }
        
        // Check access permissions
        if (!hasAccessToCustomer(customer, userId, userRole)) {
            throw new CustomException("Access denied to this customer");
        }
        
        return convertToWrapper(customer);
    }
    
    /**
     * Create a new customer
     */
    public CustomerWrapper createCustomer(CustomerRequestWrapper requestWrapper, Long createdBy) throws CustomException {
        // Generate customer code
        String customerCode = generateCustomerCode();
        
        CustomersEntity customer = new CustomersEntity();
        customer.setCustomerCode(customerCode);
        customer.setName(requestWrapper.getName());
        customer.setCompanyName(requestWrapper.getCompanyName());
        customer.setGroupName(parseGroupName(requestWrapper.getGroupName()));
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
        customer.setStatus(parseStatus(requestWrapper.getStatus()));
        customer.setAssignedTo(requestWrapper.getAssignedTo());
        customer.setCreatedBy(createdBy); // Set created by user
        
        CustomersEntity savedCustomer = customersRepo.save(customer);
        return convertToWrapper(savedCustomer);
    }
    
    /**
     * Convert Lead to Customer when status is "Closed Won"
     * This method is called from LeadsService
     */
    @Transactional
    public CustomerWrapper convertLeadToCustomer(LeadsEntity lead) throws CustomException {
        // Check if customer already exists for this lead
        String searchEmail = lead.getEmail();
        if (searchEmail != null && !searchEmail.isEmpty()) {
            // Search for existing customer by email
            List<CustomersEntity> existingCustomers = customersRepo.findByDeletedAtIsNull()
                .stream()
                .filter(c -> searchEmail.equalsIgnoreCase(c.getEmail()))
                .toList();
            
            if (!existingCustomers.isEmpty()) {
                // Customer already exists, return existing customer
                return convertToWrapper(existingCustomers.get(0));
            }
        }
        
        // Create new customer from lead
        String customerCode = generateCustomerCode();
        
        CustomersEntity customer = new CustomersEntity();
        customer.setCustomerCode(customerCode);
        customer.setName(lead.getName());
        customer.setCompanyName(lead.getName()); // Use name as company if no company field in lead
        customer.setContactPerson(lead.getName());
        customer.setEmail(lead.getEmail());
        customer.setPhone(lead.getPhone());
        customer.setAssignedTo(lead.getAssignedTo());
        customer.setCreatedBy(lead.getCreatedBy()); // Set created by from lead
        customer.setStatus(CustomersEntity.CustomerStatus.Active);
        
        // Map group name if available
        if (lead.getGroupName() != null) {
            try {
                customer.setGroupName(CustomersEntity.GroupName.valueOf(lead.getGroupName()));
            } catch (IllegalArgumentException e) {
                customer.setGroupName(CustomersEntity.GroupName.Others);
            }
        }
        
        CustomersEntity savedCustomer = customersRepo.save(customer);
        return convertToWrapper(savedCustomer);
    }
    
    /**
     * Update an existing customer
     */
    public CustomerWrapper updateCustomer(Long customerId, CustomerRequestWrapper requestWrapper, Long userId, String userRole) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
                .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));
        
        if (customer.getDeletedAt() != null) {
            throw new CustomException("Cannot update deleted customer");
        }
        
        // Check access permissions
        if (!hasAccessToCustomer(customer, userId, userRole)) {
            throw new CustomException("Access denied to update this customer");
        }
        
        // Update fields
        if (requestWrapper.getName() != null) {
            customer.setName(requestWrapper.getName());
        }
        if (requestWrapper.getCompanyName() != null) {
            customer.setCompanyName(requestWrapper.getCompanyName());
        }
        if (requestWrapper.getGroupName() != null) {
            customer.setGroupName(parseGroupName(requestWrapper.getGroupName()));
        }
        if (requestWrapper.getContactPerson() != null) {
            customer.setContactPerson(requestWrapper.getContactPerson());
        }
        if (requestWrapper.getDesignation() != null) {
            customer.setDesignation(requestWrapper.getDesignation());
        }
        if (requestWrapper.getEmail() != null) {
            customer.setEmail(requestWrapper.getEmail());
        }
        if (requestWrapper.getPhone() != null) {
            customer.setPhone(requestWrapper.getPhone());
        }
        if (requestWrapper.getAltPhone() != null) {
            customer.setAltPhone(requestWrapper.getAltPhone());
        }
        if (requestWrapper.getWebsite() != null) {
            customer.setWebsite(requestWrapper.getWebsite());
        }
        if (requestWrapper.getGstNumber() != null) {
            customer.setGstNumber(requestWrapper.getGstNumber());
        }
        if (requestWrapper.getPan() != null) {
            customer.setPan(requestWrapper.getPan());
        }
        if (requestWrapper.getAddress() != null) {
            customer.setAddress(requestWrapper.getAddress());
        }
        if (requestWrapper.getCity() != null) {
            customer.setCity(requestWrapper.getCity());
        }
        if (requestWrapper.getState() != null) {
            customer.setState(requestWrapper.getState());
        }
        if (requestWrapper.getPincode() != null) {
            customer.setPincode(requestWrapper.getPincode());
        }
        if (requestWrapper.getStatus() != null) {
            customer.setStatus(parseStatus(requestWrapper.getStatus()));
        }
        if (requestWrapper.getAssignedTo() != null) {
            customer.setAssignedTo(requestWrapper.getAssignedTo());
        }
        
        CustomersEntity updatedCustomer = customersRepo.save(customer);
        return convertToWrapper(updatedCustomer);
    }
    
    /**
     * Delete (soft delete) a customer
     */
    public void deleteCustomer(Long customerId, Long userId, String userRole) throws CustomException {
        CustomersEntity customer = customersRepo.findById(customerId)
                .orElseThrow(() -> new CustomException("Customer not found with ID: " + customerId));
        
        if (customer.getDeletedAt() != null) {
            throw new CustomException("Customer already deleted");
        }
        
        // Check access permissions
        if (!hasAccessToCustomer(customer, userId, userRole)) {
            throw new CustomException("Access denied to delete this customer");
        }
        
        customer.setDeletedAt(LocalDateTime.now());
        customersRepo.save(customer);
    }
    
    /**
     * Check if user has access to customer based on role
     */
    private boolean hasAccessToCustomer(CustomersEntity customer, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return true;
        }
        
        // Regular users can only access customers assigned to them
        return customer.getAssignedTo() != null && customer.getAssignedTo().equals(userId);
    }
    
    /**
     * Generate unique customer code with format: CUST-YYYY-NNNN
     */
    private String generateCustomerCode() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        long countInYear = customersRepo.countByCustomerCodeStartingWith("CUST-" + year);
        long nextSequence = countInYear + 1;
        return String.format("CUST-%s-%04d", year, nextSequence);
    }
    
    /**
     * Parse group name string to enum
     */
    private CustomersEntity.GroupName parseGroupName(String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            return null;
        }
        try {
            return CustomersEntity.GroupName.valueOf(groupName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * Parse status string to enum
     */
    private CustomersEntity.CustomerStatus parseStatus(String status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        try {
            return CustomersEntity.CustomerStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * Parse date string to LocalDateTime
     */
    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Convert Entity to Wrapper
     */
    private CustomerWrapper convertToWrapper(CustomersEntity entity) {
        CustomerWrapper wrapper = new CustomerWrapper();
        wrapper.setId(entity.getId());
        wrapper.setCustomerCode(entity.getCustomerCode());
        wrapper.setName(entity.getName());
        wrapper.setCompanyName(entity.getCompanyName());
        wrapper.setGroupName(entity.getGroupName() != null ? entity.getGroupName().name() : null);
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
        wrapper.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
        wrapper.setAssignedTo(entity.getAssignedTo());
        
        // Get assigned user name
        if (entity.getAssignedTo() != null) {
            usersRepo.findById(entity.getAssignedTo()).ifPresent(user -> 
                wrapper.setAssignedToName(user.getName())
            );
        }
        
        if (entity.getCreatedAt() != null) {
            wrapper.setCreatedAt(entity.getCreatedAt().format(DATE_FORMATTER));
        }
        
        if (entity.getUpdatedAt() != null) {
            wrapper.setUpdatedAt(entity.getUpdatedAt().format(DATE_FORMATTER));
        }
        
        return wrapper;
    }
    public Page<CustomerWrapper> getAllCustomersPaginated(Long userId, String userRole, 
            String groupName, String subGroupName,
            int page, int size) {
Pageable pageable = PageRequest.of(page, size);
Page<CustomersEntity> customerPage;

if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
// Admin sees all customers
if (groupName != null && !groupName.isEmpty()) {
try {
CustomersEntity.GroupName group = CustomersEntity.GroupName.valueOf(groupName);
customerPage = customersRepo.findByGroupNameAndDeletedAtIsNull(group, pageable);
} catch (IllegalArgumentException e) {
customerPage = customersRepo.findByDeletedAtIsNull(pageable);
}
} else {
customerPage = customersRepo.findByDeletedAtIsNull(pageable);
}
} else {
// Regular users see only customers created by them
if (groupName != null && !groupName.isEmpty()) {
try {
CustomersEntity.GroupName group = CustomersEntity.GroupName.valueOf(groupName);
customerPage = customersRepo.findByCreatedByAndGroupNameAndDeletedAtIsNull(userId, group, pageable);
} catch (IllegalArgumentException e) {
customerPage = customersRepo.findByCreatedByAndDeletedAtIsNull(userId, pageable);
}
} else {
customerPage = customersRepo.findByCreatedByAndDeletedAtIsNull(userId, pageable);
}
}

return customerPage.map(this::convertToWrapper);
}

/**
* Get filtered customers with pagination
*/
public Page<CustomerWrapper> getFilteredCustomersPaginated(Long userId, String userRole,
                 CustomerFilterRequestWrapper filterRequest,
                 int page, int size) {
Pageable pageable = PageRequest.of(page, size);
Page<CustomersEntity> customerPage;

// Parse filter parameters
CustomersEntity.GroupName groupName = parseGroupName(filterRequest.getGroupName());
CustomersEntity.CustomerStatus status = parseStatus(filterRequest.getStatus());
LocalDateTime fromDate = parseDate(filterRequest.getFromDate());
LocalDateTime toDate = parseDate(filterRequest.getToDate());

if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
// Admin searches all customers
customerPage = customersRepo.searchCustomersPaginated(
filterRequest.getSearchTerm(),
groupName,
status,
filterRequest.getCity(),
filterRequest.getState(),
filterRequest.getAssignedTo(),
fromDate,
toDate,
pageable
);
} else {
// Regular users search only their created customers
customerPage = customersRepo.searchCustomersForUserPaginated(
userId,
filterRequest.getSearchTerm(),
groupName,
status,
filterRequest.getCity(),
filterRequest.getState(),
fromDate,
toDate,
pageable
);
}

return customerPage.map(this::convertToWrapper);
}
}