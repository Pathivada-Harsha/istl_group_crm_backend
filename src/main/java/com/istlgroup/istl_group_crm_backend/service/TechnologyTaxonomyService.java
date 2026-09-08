package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.repo.TechnologyGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.TechnologySubGroupRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownSubGroupWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Project Details (Technology area) Group / Sub Group taxonomy — a separate
 * dataset from {@link DropdownFilterService}'s Group/SubGroup filters, so
 * that stack (used across Cost & Expense, Receipts, Vendor Payments,
 * Inventory, etc.) is never touched by this feature.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TechnologyTaxonomyService {

    private final TechnologyGroupRepository groupRepository;
    private final TechnologySubGroupRepository subGroupRepository;

    public List<DropdownGroupWrapper> getAllGroups() {
        return groupRepository.findByIsActiveTrue().stream()
            .map(g -> new DropdownGroupWrapper(g.getGroupName(), g.getGroupLabel()))
            .collect(Collectors.toList());
    }

    public List<DropdownSubGroupWrapper> getSubGroupsByGroup(String groupName) {
        return subGroupRepository.findByGroupNameAndIsActiveTrue(groupName).stream()
            .map(sg -> new DropdownSubGroupWrapper(sg.getSubGroupName(), sg.getSubGroupLabel()))
            .collect(Collectors.toList());
    }
}
