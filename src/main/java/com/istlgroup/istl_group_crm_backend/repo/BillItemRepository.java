package com.istlgroup.istl_group_crm_backend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.BillItemEntity;

@Repository
public interface BillItemRepository extends JpaRepository<BillItemEntity, Long> {
}
