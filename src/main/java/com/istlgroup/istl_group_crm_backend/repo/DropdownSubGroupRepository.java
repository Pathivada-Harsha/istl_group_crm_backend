package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DropdownSubGroupRepository extends JpaRepository<DropdownSubGroupEntity, Long> {

    @Query("SELECT sg FROM DropdownSubGroupEntity sg WHERE sg.group.groupName = :groupName AND sg.isActive = true")
    List<DropdownSubGroupEntity> findByGroupNameAndIsActiveTrue(@Param("groupName") String groupName);

    List<DropdownSubGroupEntity> findAll();
    Optional<DropdownSubGroupEntity> findBysubGroupName(String subGroupName);

    // ── Paginated search (admin page) ──────────────────────────────────────
    @Query("SELECT sg FROM DropdownSubGroupEntity sg " +
           "LEFT JOIN sg.group g " +
           "WHERE (:search IS NULL OR :search = '' " +
           "   OR LOWER(sg.subGroupName)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(sg.subGroupLabel) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupName)      LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY sg.id DESC")
    Page<DropdownSubGroupEntity> searchPaged(@Param("search") String search, Pageable pageable);

    /** Paginated search filtered by a specific parent group. */
    @Query("SELECT sg FROM DropdownSubGroupEntity sg " +
           "LEFT JOIN sg.group g " +
           "WHERE (:groupId IS NULL OR sg.group.id = :groupId) AND " +
           "(:search IS NULL OR :search = '' " +
           "   OR LOWER(sg.subGroupName)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(sg.subGroupLabel) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupName)      LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY sg.id DESC")
    Page<DropdownSubGroupEntity> searchPagedByGroup(
        @Param("groupId") Long groupId,
        @Param("search") String search,
        Pageable pageable
    );

    @Query("SELECT COUNT(sg) FROM DropdownSubGroupEntity sg " +
           "LEFT JOIN sg.group g " +
           "WHERE (:search IS NULL OR :search = '' " +
           "   OR LOWER(sg.subGroupName)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(sg.subGroupLabel) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupName)      LIKE LOWER(CONCAT('%', :search, '%')))")
    long countSearch(@Param("search") String search);
}