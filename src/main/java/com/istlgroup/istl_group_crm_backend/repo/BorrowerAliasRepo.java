package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerAliasEntity;

public interface BorrowerAliasRepo extends JpaRepository<BorrowerAliasEntity, Long> {

    List<BorrowerAliasEntity> findByBorrowerId(Long borrowerId);
}
