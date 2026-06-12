package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.BillItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillItemRepository extends JpaRepository<BillItemEntity, Long> {

    @Query("SELECT bi FROM BillItemEntity bi WHERE bi.bill.id = :billId")
    List<BillItemEntity> findByBillId(@Param("billId") Long billId);
}