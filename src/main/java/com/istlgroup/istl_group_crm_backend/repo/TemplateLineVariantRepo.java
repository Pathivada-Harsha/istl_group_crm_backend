package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.TemplateLineVariantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateLineVariantRepo extends JpaRepository<TemplateLineVariantEntity, Long> {

    // Allowed makes attached to one template line.
    List<TemplateLineVariantEntity> findByTemplateItemId(Long templateItemId);

    // Batch lookup — enrich a whole suggested BOM at once.
    List<TemplateLineVariantEntity> findByTemplateItemIdIn(List<Long> templateItemIds);

    // Replace-on-save: clear a line's allowed set before re-writing it. Bulk
    // delete (executes immediately) so re-inserting the same variant_ids in the
    // same transaction can't trip the (template_item_id, variant_id) unique key.
    @Modifying
    @Query("DELETE FROM TemplateLineVariantEntity t WHERE t.templateItemId = :templateItemId")
    void deleteByTemplateItemId(@Param("templateItemId") Long templateItemId);
}
