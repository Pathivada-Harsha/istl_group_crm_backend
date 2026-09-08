package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.TechnologyTaxonomyService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownSubGroupWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Project Details (Technology area) Group / Sub Group dropdowns — a
 * standalone taxonomy, separate from {@code DropdownFilterController}'s
 * existing Group/SubGroup filters used elsewhere in the app.
 */
@RestController
@RequestMapping("/technology-taxonomy")
@RequiredArgsConstructor
public class TechnologyTaxonomyController {

    private final TechnologyTaxonomyService taxonomyService;

    @GetMapping("/groups")
    public ResponseEntity<List<DropdownGroupWrapper>> getAllGroups() {
        return ResponseEntity.ok(taxonomyService.getAllGroups());
    }

    @GetMapping("/subgroups")
    public ResponseEntity<List<DropdownSubGroupWrapper>> getSubGroups(@RequestParam String groupName) {
        return ResponseEntity.ok(taxonomyService.getSubGroupsByGroup(groupName));
    }
}
