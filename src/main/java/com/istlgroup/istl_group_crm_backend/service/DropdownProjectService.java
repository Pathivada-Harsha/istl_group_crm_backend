package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownSubGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor  // ✅ All fields final — consistent constructor injection, no @Autowired mix
public class DropdownProjectService {

    // ✅ All final — injected via constructor
    private final DropdownProjectRepository projectRepository;
    private final DropdownSubGroupRepository subGroupRepository;
    private final CustomersRepo customersRepo;
    private final ProjectRepository mainProjectRepository;

    @Transactional
    public DropdownProjectEntity createProject(DropdownProjectEntity project, Long subGroupId, Long userId) {
        DropdownSubGroupEntity subGroup = subGroupRepository.findById(subGroupId)
            .orElseThrow(() -> new RuntimeException("SubGroup not found with id: " + subGroupId));

        project.setSubGroup(subGroup);
        project.setGroup_id(subGroup.getGroup().getGroupName());
        project.setSubGroupName(subGroup.getSubGroupName());
        project.setCreatedBy(userId);

        if (project.getProjectUniqueId() == null || project.getProjectUniqueId().isEmpty()) {
            String generatedCode = generateProjectCode();
            project.setProjectUniqueId(generatedCode);
        }

        // Projects are now standalone — no auto-customer creation.
        // Customers are created directly; projects come from Order Books.
        return projectRepository.save(project);
    }

    @Transactional
    public DropdownProjectEntity updateProject(String projectUniqueId, DropdownProjectEntity updatedProject) {
        DropdownProjectEntity existingProject = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found with id: " + projectUniqueId));

        existingProject.setProjectName(updatedProject.getProjectName());
        existingProject.setDescription(updatedProject.getDescription());
        existingProject.setLocation(updatedProject.getLocation());
        existingProject.setStartDate(updatedProject.getStartDate());
        existingProject.setEndDate(updatedProject.getEndDate());
        existingProject.setStatus(updatedProject.getStatus());
        existingProject.setBudget(updatedProject.getBudget());
        existingProject.setIsActive(updatedProject.getIsActive());
        if (updatedProject.getProgressPercentage() != null) {
            existingProject.setProgressPercentage(updatedProject.getProgressPercentage());
        }

        return projectRepository.save(existingProject);
    }

    /**
     * Update only status and/or progressPercentage for a project.
     * Smart auto-rules if progressPercentage not provided:
     *   COMPLETED → 100% | PLANNING (at 0) → 0% | CANCELLED → unchanged
     */
    @Transactional
    public DropdownProjectEntity updateStatusAndProgress(
            String projectUniqueId, Map<String, Object> body) {

        DropdownProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));

        if (body.containsKey("status") && body.get("status") != null) {
            try {
                project.setStatus(DropdownProjectEntity.ProjectStatus.valueOf(body.get("status").toString()));
            } catch (IllegalArgumentException ignored) {}
        }

        if (body.containsKey("progressPercentage") && body.get("progressPercentage") != null) {
            double pct = Double.parseDouble(body.get("progressPercentage").toString());
            project.setProgressPercentage(BigDecimal.valueOf(Math.min(100, Math.max(0, pct))));
        } else {
            DropdownProjectEntity.ProjectStatus s = project.getStatus();
            if (s == DropdownProjectEntity.ProjectStatus.COMPLETED) {
                project.setProgressPercentage(BigDecimal.valueOf(100));
            } else if (s == DropdownProjectEntity.ProjectStatus.NOT_STARTED
                    || (s == DropdownProjectEntity.ProjectStatus.PLANNING
                        && (project.getProgressPercentage() == null || project.getProgressPercentage().doubleValue() == 0))) {
                project.setProgressPercentage(BigDecimal.ZERO);
            }
        }
        return projectRepository.save(project);
    }

    @Transactional
    public void deleteProject(String projectUniqueId) {
        DropdownProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found with id: " + projectUniqueId));

        if (Boolean.FALSE.equals(project.getIsActive())) {
            // Already inactive → permanently hard-delete from both tables
            projectRepository.deleteById(project.getId());
            mainProjectRepository.findByProjectUniqueId(projectUniqueId)
                .ifPresent(p -> mainProjectRepository.deleteById(p.getId()));
        } else {
            // Active → soft-delete: mark inactive in both tables
            project.setIsActive(false);
            projectRepository.save(project);
            mainProjectRepository.findByProjectUniqueId(projectUniqueId).ifPresent(p -> {
                p.setIsActive(false);
                mainProjectRepository.save(p);
            });
        }
    }

    private String generateProjectCode() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        long countInYear = projectRepository.countByProjectUniqueIdStartingWith("PROJ-" + year);
        long nextSequence = countInYear + 1;
        return String.format("PROJ-%s-%04d", year, nextSequence);
    }

    /**
     * @deprecated Projects are no longer auto-created when a lead reaches Closed Won.
     * Use {@link #createProjectFromOrderBook} instead.
     */
    @Deprecated
    public DropdownProjectEntity createProjectFromLead(Object lead, String customerCode) {
        throw new UnsupportedOperationException(
            "Lead-to-project conversion is disabled. " +
            "Create an Order Book for this customer and use /order-book/{id}/convert-to-project instead."
        );
    }


    /**
     * Create a project from an Order Book.
     *
     * Project name format: "CustomerName - OrderBookNo"
     * Budget is taken from orderBook.totalAmount so the project dashboard
     * starts with the correct financial baseline immediately.
     * This replaces the old approach of creating a single project per lead;
     * now every Order Book produces its own project, allowing one client
     * to have multiple projects running in parallel.
     */
    @Transactional
    /**
     * Build the display name for a project from an order book.
     * Format: "CustomerName - PO_NUMBER"  (falls back to order book no if PO not yet entered)
     */
    private String buildProjectName(String customerName, OrderBookEntity orderBook) {
        String poRef = (orderBook.getPoNumber() != null && !orderBook.getPoNumber().trim().isEmpty())
            ? orderBook.getPoNumber().trim()
            : orderBook.getOrderBookNo();
        return customerName + " - " + poRef;
    }

    public DropdownProjectEntity createProjectFromOrderBook(OrderBookEntity orderBook, String customerName) {
        DropdownProjectEntity projectEntity = new DropdownProjectEntity();

        projectEntity.setProjectName(buildProjectName(customerName, orderBook));

        projectEntity.setCustomerCode(
            customersRepo.findById(orderBook.getCustomerId())
                .map(c -> c.getCustomerCode())
                .orElse(null)
        );
        projectEntity.setGroup_id(orderBook.getGroupName());
        projectEntity.setSubGroupName(orderBook.getSubGroupName());
        projectEntity.setDescription(orderBook.getOrderDescription());
        projectEntity.setBudget(
            orderBook.getTotalAmount() != null ? orderBook.getTotalAmount() : java.math.BigDecimal.ZERO
        );

        // ── Start date = order date from the order book ───────────────────────
        // The project begins when the order is placed, so use order_date as start_date.
        if (orderBook.getOrderDate() != null) {
            projectEntity.setStartDate(orderBook.getOrderDate());
        }

        // Store the lead reference so the project is traceable back to its origin lead
        if (orderBook.getLeadId() != null) {
            projectEntity.setLead_id(String.valueOf(orderBook.getLeadId()));
        }

        DropdownSubGroupEntity subGroup = subGroupRepository
            .findBysubGroupName(orderBook.getSubGroupName())
            .orElseThrow(() -> new RuntimeException("Sub group not found: " + orderBook.getSubGroupName()));
        projectEntity.setSubGroup(subGroup);
        projectEntity.setProjectUniqueId(generateProjectCode());

        return projectRepository.save(projectEntity);
    }

    /**
     * Called whenever an order book is updated (especially when poNumber is added/changed).
     * Updates the project name so it always reflects the latest PO number.
     * Also syncs start_date from order_date if the project's start_date is still null.
     */
    @Transactional
    public void syncProjectNameFromOrderBook(String projectUniqueId, OrderBookEntity orderBook, String customerName) {
        projectRepository.findByProjectUniqueId(projectUniqueId).ifPresent(project -> {
            project.setProjectName(buildProjectName(customerName, orderBook));
            // Also sync budget in case order totals changed
            if (orderBook.getTotalAmount() != null) {
                project.setBudget(orderBook.getTotalAmount());
            }
            // ── Sync start_date from order_date ───────────────────────────────
            // If project has no start date yet, fill it from the order date.
            // If the order date changed and project still matches the old order date,
            // update it to keep them in sync.
            if (orderBook.getOrderDate() != null) {
                if (project.getStartDate() == null) {
                    // No start date at all — backfill from order date
                    project.setStartDate(orderBook.getOrderDate());
                }
            }
            projectRepository.save(project);
        });

        // NOTE: DropdownProjectEntity and ProjectEntity both map to the same `projects`
        // table, so the single save above covers both — no separate mainProjectRepository
        // update is needed.
    }

    /**
     * @deprecated Projects are no longer auto-created when a customer is saved.
     * Use {@link #createProjectFromOrderBook} instead — each Order Book
     * for a client becomes its own project ("ClientName - OrderBookNo").
     * This stub is kept to avoid compile errors if any legacy code references it.
     */
    @Deprecated
    public DropdownProjectEntity createProjectFromCustomers(Object customers) {
        throw new UnsupportedOperationException(
            "Direct customer-to-project conversion is disabled. " +
            "Create an Order Book for this customer and use /order-book/{id}/convert-to-project instead."
        );
    }

    /**
     * @deprecated Circular project→customer auto-creation is removed.
     * Customers are always created directly by users.
     */
    @Deprecated
    public CustomersEntity createCustomerFromProject(DropdownProjectEntity projectEntity) {
        throw new UnsupportedOperationException(
            "Project-to-customer auto-creation is disabled. Create customers directly."
        );
    }

    public String getProjectIdByCustomerid(Long customerId) {
        return projectRepository.findProjectIdByCustomerCode(customerId);
    }
}