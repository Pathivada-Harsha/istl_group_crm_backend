package com.istlgroup.istl_group_crm_backend.service;


import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownProjectWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownSubGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsUserWrapper;
import com.istlgroup.istl_group_crm_backend.entity.DropdownGroupEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownSubGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DropdownFilterService {
	@Autowired
    private UsersRepo usersRepo;
    private final DropdownGroupRepository groupRepository;
    private final DropdownSubGroupRepository subGroupRepository;
    private final DropdownProjectRepository projectRepository;
    
    public List<DropdownGroupWrapper> getAllGroups() {
        return groupRepository.findByIsActiveTrue().stream()
            .map(group -> new DropdownGroupWrapper(group.getGroupName(), group.getGroupLabel()))
            .collect(Collectors.toList());
    }
    
    public List<DropdownSubGroupWrapper> getSubGroupsByGroup(String groupName) {
        return subGroupRepository.findByGroupNameAndIsActiveTrue(groupName).stream()
            .map(subGroup -> new DropdownSubGroupWrapper(
                subGroup.getSubGroupName(),
                subGroup.getSubGroupLabel()
            ))
            .collect(Collectors.toList());
    }
    
    public List<DropdownProjectWrapper> getProjectsByGroupAndSubGroup(String groupName, String subGroupName) {
        return projectRepository.findByGroupAndSubGroup(groupName, subGroupName).stream()
            .map(project -> new DropdownProjectWrapper(
                project.getProjectUniqueId(),
                project.getProjectName(),
                project.getLocation(),
                project.getStatus().name()
            ))
            .collect(Collectors.toList());
    }
    
    public DropdownProjectEntity getProjectByUniqueId(String projectUniqueId) {
        return projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));
    }
    public List<LeadsUserWrapper> getLeadsUsers() {
        return usersRepo.findAllActiveUsers().stream()
            .map(user -> new LeadsUserWrapper(
                user.getId(),
                user.getName() != null ? user.getName() : user.getUser_id()
            ))
            .collect(Collectors.toList());
    }
}