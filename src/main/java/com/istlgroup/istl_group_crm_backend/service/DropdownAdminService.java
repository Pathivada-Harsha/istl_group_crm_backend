package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.DropdownGroupEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownSubGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DropdownAdminService {

    private final DropdownGroupRepository    groupRepository;
    private final DropdownSubGroupRepository subGroupRepository;
    private final DropdownProjectRepository  projectRepository;

    // ── Existing unpaged methods (used by filter dropdowns everywhere) ──────

    public List<DropdownGroupEntity> getAllGroupsAdmin() {
        return groupRepository.findAll();
    }

    public DropdownGroupEntity getGroupById(Long id) {
        return groupRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Group not found: " + id));
    }

    @Transactional
    public DropdownGroupEntity createGroup(DropdownGroupEntity group) {
        return groupRepository.save(group);
    }

    @Transactional
    public DropdownGroupEntity updateGroup(Long id, DropdownGroupEntity updated) {
        DropdownGroupEntity existing = getGroupById(id);
        existing.setGroupName(updated.getGroupName());
        existing.setGroupLabel(updated.getGroupLabel());
        existing.setDescription(updated.getDescription());
        existing.setIsActive(updated.getIsActive());
        return groupRepository.save(existing);
    }

    @Transactional
    public void deleteGroup(Long id) {
        DropdownGroupEntity group = getGroupById(id);
        if (Boolean.FALSE.equals(group.getIsActive())) {
            // Already inactive → permanently hard-delete from DB
            groupRepository.deleteById(id);
        } else {
            // Active → soft-delete: mark inactive only
            group.setIsActive(false);
            groupRepository.save(group);
        }
    }

    public List<DropdownSubGroupEntity> getAllSubGroupsAdmin() {
        return subGroupRepository.findAll();
    }

    public DropdownSubGroupEntity getSubGroupById(Long id) {
        return subGroupRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("SubGroup not found: " + id));
    }

    @Transactional
    public DropdownSubGroupEntity createSubGroup(DropdownSubGroupEntity subGroup, Long groupId) {
        DropdownGroupEntity group = getGroupById(groupId);
        subGroup.setGroup(group);
        return subGroupRepository.save(subGroup);
    }

    @Transactional
    public DropdownSubGroupEntity updateSubGroup(Long id, DropdownSubGroupEntity updated) {
        DropdownSubGroupEntity existing = getSubGroupById(id);
        existing.setSubGroupName(updated.getSubGroupName());
        existing.setSubGroupLabel(updated.getSubGroupLabel());
        existing.setDescription(updated.getDescription());
        existing.setIsActive(updated.getIsActive());
        return subGroupRepository.save(existing);
    }

    @Transactional
    public void deleteSubGroup(Long id) {
        DropdownSubGroupEntity sg = getSubGroupById(id);
        if (Boolean.FALSE.equals(sg.getIsActive())) {
            // Already inactive → permanently hard-delete from DB
            subGroupRepository.deleteById(id);
        } else {
            // Active → soft-delete: mark inactive only
            sg.setIsActive(false);
            subGroupRepository.save(sg);
        }
    }

    public List<DropdownProjectEntity> getAllProjectsAdmin() {
        return projectRepository.findAll();
    }

    public DropdownProjectEntity getProjectById(Long id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Project not found: " + id));
    }

    // ── NEW: paginated search methods for the admin management page ─────────

    /**
     * Returns a Spring Page of groups matching the optional search term.
     * The response is serialised to:
     *   { content:[...], totalElements:N, totalPages:P, size:S, number:0 }
     */
    public Map<String, Object> getGroupsPaged(String search, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size);
        String s = (search != null && !search.isBlank()) ? search.trim() : "";
        Page<DropdownGroupEntity> p = groupRepository.searchPaged(s, pageable);
        return buildPageResponse(p);
    }

    public Map<String, Object> getSubGroupsPaged(String search, int page, int size, Long groupId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size);
        String s = (search != null && !search.isBlank()) ? search.trim() : "";
        Page<DropdownSubGroupEntity> p = (groupId != null)
            ? subGroupRepository.searchPagedByGroup(groupId, s, pageable)
            : subGroupRepository.searchPaged(s, pageable);
        return buildPageResponse(p);
    }

    public Map<String, Object> getProjectsPaged(String search, int page, int size, Long groupId, Long subGroupId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size);
        String s = (search != null && !search.isBlank()) ? search.trim() : "";
        Page<DropdownProjectEntity> p = (groupId != null || subGroupId != null)
            ? projectRepository.searchPagedByGroupAndSubGroup(groupId, subGroupId, s, pageable)
            : projectRepository.searchPaged(s, pageable);
        return buildPageResponse(p);
    }

    // ── Helper: convert Spring Page to consistent response map ──────────────
    private <T> Map<String, Object> buildPageResponse(Page<T> page) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("content",       page.getContent());
        resp.put("totalElements", page.getTotalElements());
        resp.put("totalPages",    page.getTotalPages());
        resp.put("size",          page.getSize());
        resp.put("number",        page.getNumber());   // 0-indexed current page
        resp.put("first",         page.isFirst());
        resp.put("last",          page.isLast());
        return resp;
    }
}