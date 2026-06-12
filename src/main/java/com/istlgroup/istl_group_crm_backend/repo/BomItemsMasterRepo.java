package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomItemsMasterRepo extends JpaRepository<BomItemsMasterEntity, Long> {

    // Find all active items
    List<BomItemsMasterEntity> findByIsActiveTrue();

    // Find by category
    List<BomItemsMasterEntity> findByCategoryAndIsActiveTrue(String category);

    // Search items by name or description (for autocomplete)
    @Query("SELECT b FROM BomItemsMasterEntity b WHERE b.isActive = true AND " +
           "(LOWER(b.itemName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(b.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(b.specification) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<BomItemsMasterEntity> searchBomItems(@Param("searchTerm") String searchTerm);

    // Search items by category and search term
    @Query("SELECT b FROM BomItemsMasterEntity b WHERE b.isActive = true AND " +
           "(:category IS NULL OR b.category = :category) AND " +
           "(LOWER(b.itemName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(b.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(b.specification) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<BomItemsMasterEntity> searchBomItemsByCategory(
        @Param("category") String category,
        @Param("searchTerm") String searchTerm
    );

    // Get distinct categories
    @Query("SELECT DISTINCT b.category FROM BomItemsMasterEntity b WHERE b.isActive = true")
    List<String> findDistinctCategories();
}