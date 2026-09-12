package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import com.istlgroup.istl_group_crm_backend.entity.InfrastructureCategoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.InfrastructureMasterListVersionEntity;
import com.istlgroup.istl_group_crm_backend.entity.InfrastructureSubCategoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.TechnologyGroupEntity;
import com.istlgroup.istl_group_crm_backend.entity.TechnologySubGroupEntity;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureCategoryRepository;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureMasterListVersionRepository;
import com.istlgroup.istl_group_crm_backend.repo.InfrastructureSubCategoryRepository;
import com.istlgroup.istl_group_crm_backend.repo.TechnologyGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.TechnologySubGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.istlgroup.istl_group_crm_backend.service.infrastructure.InfrastructureMasterListSourceService.DiscoveredDocument;

/**
 * The single transactional unit that activates a new, already-validated
 * Harmonized Master List version: insert the version + its category/
 * sub-category snapshot, flip {@code isCurrent}, and upsert the live
 * {@code technology_groups}/{@code technology_sub_groups} tables that the
 * existing Sanction Letter dropdown API reads from. A separate
 * {@code @Component} (rather than a method on {@link InfrastructureMasterListSyncService})
 * so {@code @Transactional} applies even though the sync service calls it —
 * a same-class call would bypass Spring's transactional proxy.
 *
 * <p>Rows are never deleted from {@code technology_groups}/
 * {@code technology_sub_groups} — only inserted, updated, or deactivated —
 * so historical sanction letters (which store the plain group/sub-group
 * name string, not a foreign key) keep displaying whatever they originally
 * recorded even after that name drops out of the current government list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InfrastructureMasterListVersionWriter {

    private final InfrastructureMasterListVersionRepository versionRepository;
    private final InfrastructureCategoryRepository categoryRepository;
    private final InfrastructureSubCategoryRepository subCategoryRepository;
    private final TechnologyGroupRepository technologyGroupRepository;
    private final TechnologySubGroupRepository technologySubGroupRepository;

    @Transactional
    public InfrastructureMasterListVersionEntity activateNewVersion(DiscoveredDocument document,
                                                                      String documentHash,
                                                                      List<ParsedCategory> categories) {
        versionRepository.findByIsCurrentTrue().ifPresent(previous -> {
            previous.setIsCurrent(false);
            versionRepository.save(previous);
        });

        InfrastructureMasterListVersionEntity version = new InfrastructureMasterListVersionEntity();
        version.setVersionLabel(document.title());
        version.setNotificationNumber(document.referenceNo());
        version.setNotificationDate(document.notificationDate());
        version.setSourcePageUrl(document.sourcePageUrl());
        version.setPdfUrl(document.pdfUrl());
        version.setDocumentHash(documentHash);
        version.setFetchedAt(LocalDateTime.now());
        version.setIsCurrent(true);
        version = versionRepository.save(version);

        for (ParsedCategory category : categories) {
            InfrastructureCategoryEntity categoryEntity = new InfrastructureCategoryEntity();
            categoryEntity.setVersionId(version.getId());
            categoryEntity.setName(category.name());
            categoryEntity.setDisplayOrder(category.displayOrder());
            categoryEntity = categoryRepository.save(categoryEntity);

            for (ParsedSubCategory sub : category.subCategories()) {
                InfrastructureSubCategoryEntity subEntity = new InfrastructureSubCategoryEntity();
                subEntity.setCategoryId(categoryEntity.getId());
                subEntity.setName(sub.name());
                subEntity.setDisplayOrder(sub.displayOrder());
                subCategoryRepository.save(subEntity);
            }
        }

        upsertLiveDropdownTables(categories);
        log.info("Infrastructure Master List: activated version '{}' ({} categories)",
                document.title(), categories.size());
        return version;
    }

    private void upsertLiveDropdownTables(List<ParsedCategory> categories) {
        Map<String, TechnologyGroupEntity> existingGroups = new HashMap<>();
        for (TechnologyGroupEntity g : technologyGroupRepository.findAll()) {
            existingGroups.put(normalize(g.getGroupName()), g);
        }

        for (ParsedCategory category : categories) {
            String key = normalize(category.name());
            TechnologyGroupEntity group = existingGroups.get(key);
            if (group == null) {
                group = new TechnologyGroupEntity();
                group.setGroupName(category.name());
            }
            group.setGroupLabel(category.name());
            group.setIsActive(true);
            group = technologyGroupRepository.save(group);
            existingGroups.put(key, group);

            upsertLiveSubGroups(group, category.subCategories());
        }

        for (TechnologyGroupEntity g : existingGroups.values()) {
            if (Boolean.TRUE.equals(g.getIsActive())
                    && categories.stream().noneMatch(c -> normalize(c.name()).equals(normalize(g.getGroupName())))) {
                g.setIsActive(false);
                technologyGroupRepository.save(g);
            }
        }
    }

    private void upsertLiveSubGroups(TechnologyGroupEntity group, List<ParsedSubCategory> subCategories) {
        Map<String, TechnologySubGroupEntity> existing = new HashMap<>();
        for (TechnologySubGroupEntity sg : technologySubGroupRepository.findAll()) {
            if (sg.getGroupId() != null && sg.getGroupId().equals(group.getId())) {
                existing.put(normalize(sg.getSubGroupName()), sg);
            }
        }

        for (ParsedSubCategory sub : subCategories) {
            String key = normalize(sub.name());
            TechnologySubGroupEntity entity = existing.get(key);
            if (entity == null) {
                entity = new TechnologySubGroupEntity();
                entity.setGroupId(group.getId());
                entity.setSubGroupName(sub.name());
            }
            entity.setSubGroupLabel(sub.name());
            entity.setIsActive(true);
            technologySubGroupRepository.save(entity);
            existing.put(key, entity);
        }

        List<String> currentKeys = subCategories.stream().map(s -> normalize(s.name())).toList();
        for (TechnologySubGroupEntity sg : existing.values()) {
            if (Boolean.TRUE.equals(sg.getIsActive()) && !currentKeys.contains(normalize(sg.getSubGroupName()))) {
                sg.setIsActive(false);
                technologySubGroupRepository.save(sg);
            }
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
