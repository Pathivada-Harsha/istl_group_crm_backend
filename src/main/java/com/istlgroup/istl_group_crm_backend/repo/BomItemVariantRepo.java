package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomItemVariantRepo extends JpaRepository<BomItemVariantEntity, Long> {

    // Active makes for one catalog item (for the pick-a-make dropdown).
    List<BomItemVariantEntity> findByBomItemIdAndIsActiveTrueOrderByMakeAsc(Long bomItemId);

    // All makes (incl. inactive) for one item — for admin management.
    List<BomItemVariantEntity> findByBomItemIdOrderByMakeAsc(Long bomItemId);
}
