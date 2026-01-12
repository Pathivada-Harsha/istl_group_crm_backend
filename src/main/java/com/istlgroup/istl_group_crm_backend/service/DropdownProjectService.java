package com.istlgroup.istl_group_crm_backend.service;


import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownSubGroupRepository;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DropdownProjectService {
    
    private final DropdownProjectRepository projectRepository;
    private final DropdownSubGroupRepository subGroupRepository;
    
    @Transactional
    public DropdownProjectEntity createProject(DropdownProjectEntity project, Long subGroupId) {
        DropdownSubGroupEntity subGroup = subGroupRepository.findById(subGroupId)
            .orElseThrow(() -> new RuntimeException("SubGroup not found with id: " + subGroupId));
        
        project.setSubGroup(subGroup);
        
        // Generate unique project ID if not provided
        if (project.getProjectUniqueId() == null || project.getProjectUniqueId().isEmpty()) {
            generateProjectCode();
        }
        
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
        
        return projectRepository.save(existingProject);
    }
    
    @Transactional
    public void deleteProject(String projectUniqueId) {
        DropdownProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found with id: " + projectUniqueId));
        
        // Soft delete
        project.setIsActive(false);
        projectRepository.save(project);
    }
    
    private String generateUniqueProjectId(DropdownSubGroupEntity subGroup) {
        String groupPrefix = subGroup.getGroup().getGroupName().toUpperCase();
        String subGroupPrefix = subGroup.getSubGroupName().toUpperCase()
            .replace(" ", "-")
            .replace("(", "")
            .replace(")", "");
        
        // Remove extra hyphens
        subGroupPrefix = subGroupPrefix.replaceAll("-+", "-");
        if (subGroupPrefix.endsWith("-")) {
            subGroupPrefix = subGroupPrefix.substring(0, subGroupPrefix.length() - 1);
        }
        
        String prefix = groupPrefix + "-" + subGroupPrefix + "-";
        
        String maxId = projectRepository.findMaxProjectIdByPrefix(prefix);
        
        int nextNumber = 1;
        if (maxId != null && !maxId.isEmpty()) {
            try {
                String numberPart = maxId.substring(prefix.length());
                nextNumber = Integer.parseInt(numberPart) + 1;
            } catch (Exception e) {
                // If parsing fails, start from 1
                nextNumber = 1;
            }
        }
        
        return prefix + String.format("%03d", nextNumber);
    }
    private String generateProjectCode() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        
        // Get count of leads with codes starting with "LEAD-2025"
        long countInYear = projectRepository.countByProjectUniqueIdStartingWith("PROJ-" + year);
        long nextSequence = countInYear + 1;
        
        // Use 4 digits minimum, expands automatically beyond 9999
        return String.format("PROJ-%s-%04d", year, nextSequence);
    }
    
    public DropdownProjectEntity createProjectFromLead( LeadsEntity Lead) {
		// TODO Auto-generated method stub
		DropdownProjectEntity projectEntity = new DropdownProjectEntity();
		projectEntity.setLead_id(Lead.getLeadCode());
		projectEntity.setGroup_id(Lead.getGroupName());
		projectEntity.setSubGroupName(Lead.getSubGroupName());
		projectEntity.setProjectName(Lead.getName());
		projectEntity.setDescription(Lead.getEnquiry());
		projectEntity.setCreatedAt(Lead.getCreatedAt());
		DropdownSubGroupEntity subGroup =
	            subGroupRepository.findBysubGroupName(Lead.getSubGroupName())
	            .orElseThrow(() -> new RuntimeException("Sub group not found"));

	    // ✅ set relation (FK)
		projectEntity.setSubGroup(subGroup);

		 if (projectEntity.getProjectUniqueId() == null || projectEntity.getProjectUniqueId().isEmpty()) {
			 projectEntity.setProjectUniqueId(generateProjectCode());
	        }
		 DropdownProjectEntity ent = projectRepository.save(projectEntity);
		return  ent;
	}
    
}