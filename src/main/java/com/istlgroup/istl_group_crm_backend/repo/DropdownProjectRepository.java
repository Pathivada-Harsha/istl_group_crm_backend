package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DropdownProjectRepository extends JpaRepository<DropdownProjectEntity, Long> {

    @Query("SELECT p FROM DropdownProjectEntity p " +
           "JOIN p.subGroup sg " +
           "JOIN sg.group g " +
           "WHERE g.groupName = :groupName " +
           "AND sg.subGroupName = :subGroupName " +
           "AND p.isActive = true")
    List<DropdownProjectEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName
    );

    Optional<DropdownProjectEntity> findByProjectUniqueId(String projectUniqueId);

    @Query("SELECT MAX(p.projectUniqueId) FROM DropdownProjectEntity p WHERE p.projectUniqueId LIKE :prefix%")
    String findMaxProjectIdByPrefix(@Param("prefix") String prefix);

    List<DropdownProjectEntity> findAll();

    long countByProjectUniqueIdStartingWith(String prefix);

    @Query(" SELECT projectUniqueId from DropdownProjectEntity p where p.customerCode = " +
           " (select c.customerCode from CustomersEntity c where c.id = :customerId)")
    String findProjectIdByCustomerCode(@Param("customerId") Long customerId);

    // ── Paginated search (admin page) ──────────────────────────────────────
    @Query("SELECT p FROM DropdownProjectEntity p " +
           "LEFT JOIN p.subGroup sg " +
           "LEFT JOIN sg.group g " +
           "WHERE (:search IS NULL OR :search = '' " +
           "   OR LOWER(p.projectName)      LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(p.projectUniqueId)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(p.location)         LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupName)        LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(sg.subGroupName)    LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY p.id DESC")
    Page<DropdownProjectEntity> searchPaged(@Param("search") String search, Pageable pageable);

    /** Paginated search filtered by group and/or sub-group. */
    @Query("SELECT p FROM DropdownProjectEntity p " +
           "LEFT JOIN p.subGroup sg " +
           "LEFT JOIN sg.group g " +
           "WHERE (:groupId IS NULL OR sg.group.id = :groupId) AND " +
           "(:subGroupId IS NULL OR p.subGroup.id = :subGroupId) AND " +
           "(:search IS NULL OR :search = '' " +
           "   OR LOWER(p.projectName)      LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(p.projectUniqueId)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(p.location)         LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupName)        LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(sg.subGroupName)    LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY p.id DESC")
    Page<DropdownProjectEntity> searchPagedByGroupAndSubGroup(
        @Param("groupId")    Long groupId,
        @Param("subGroupId") Long subGroupId,
        @Param("search")     String search,
        Pageable pageable
    );

    @Query("SELECT COUNT(p) FROM DropdownProjectEntity p " +
           "LEFT JOIN p.subGroup sg " +
           "LEFT JOIN sg.group g " +
           "WHERE (:search IS NULL OR :search = '' " +
           "   OR LOWER(p.projectName)      LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(p.projectUniqueId)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(p.location)         LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupName)        LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(sg.subGroupName)    LIKE LOWER(CONCAT('%', :search, '%')))")
    long countSearch(@Param("search") String search);
}