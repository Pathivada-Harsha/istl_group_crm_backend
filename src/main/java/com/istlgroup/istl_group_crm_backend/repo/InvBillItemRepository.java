package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvBillItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvBillItemRepository extends JpaRepository<InvBillItemEntity, Long> {

    List<InvBillItemEntity> findByBillId(Long billId);
}