package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InfrastructureSubCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfrastructureSubCategoryRepository extends JpaRepository<InfrastructureSubCategoryEntity, Long> {

    List<InfrastructureSubCategoryEntity> findByCategoryIdOrderByDisplayOrder(Long categoryId);

    List<InfrastructureSubCategoryEntity> findByCategoryIdInOrderByDisplayOrder(List<Long> categoryIds);
}
