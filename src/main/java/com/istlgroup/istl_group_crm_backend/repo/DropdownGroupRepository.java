package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.DropdownGroupEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DropdownGroupRepository extends JpaRepository<DropdownGroupEntity, Long> {

    List<DropdownGroupEntity> findByIsActiveTrue();
    List<DropdownGroupEntity> findAll();
    Optional<DropdownGroupEntity> findByGroupName(String groupName);

    // ── Paginated search (admin page) ──────────────────────────────────────
    @Query("SELECT g FROM DropdownGroupEntity g " +
           "WHERE (:search IS NULL OR :search = '' " +
           "   OR LOWER(g.groupName)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupLabel) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY g.id DESC")
    Page<DropdownGroupEntity> searchPaged(@Param("search") String search, Pageable pageable);

    @Query("SELECT COUNT(g) FROM DropdownGroupEntity g " +
           "WHERE (:search IS NULL OR :search = '' " +
           "   OR LOWER(g.groupName)  LIKE LOWER(CONCAT('%', :search, '%')) " +
           "   OR LOWER(g.groupLabel) LIKE LOWER(CONCAT('%', :search, '%')))")
    long countSearch(@Param("search") String search);
}