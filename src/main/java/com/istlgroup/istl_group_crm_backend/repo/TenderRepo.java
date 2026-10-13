package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.istlgroup.istl_group_crm_backend.entity.TenderEntity;

public interface TenderRepo extends JpaRepository<TenderEntity, Long> {

    List<TenderEntity> findByDeletedAtIsNullOrderByCreatedAtDesc();

    Optional<TenderEntity> findByIdAndDeletedAtIsNull(Long id);

    /** Row lock: serialises concurrent status/assignment changes on one tender. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TenderEntity t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<TenderEntity> findByIdForUpdate(@Param("id") Long id);

    List<TenderEntity> findByStatusAndDeletedAtIsNull(String status);

    List<TenderEntity> findByFinancialYearAndDeletedAtIsNull(String financialYear);
}
