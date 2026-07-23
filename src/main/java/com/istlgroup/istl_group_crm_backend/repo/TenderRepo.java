package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.TenderEntity;

public interface TenderRepo extends JpaRepository<TenderEntity, Long> {

    List<TenderEntity> findByDeletedAtIsNullOrderByCreatedAtDesc();

    Optional<TenderEntity> findByIdAndDeletedAtIsNull(Long id);

    List<TenderEntity> findByStatusAndDeletedAtIsNull(String status);

    List<TenderEntity> findByFinancialYearAndDeletedAtIsNull(String financialYear);
}
