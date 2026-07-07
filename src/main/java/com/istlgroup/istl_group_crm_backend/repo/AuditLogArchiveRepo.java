package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.AuditLogArchiveEntity;

public interface AuditLogArchiveRepo
        extends JpaRepository<AuditLogArchiveEntity, Long>,
                JpaSpecificationExecutor<AuditLogArchiveEntity> {

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM audit_logs_v2_archive WHERE created_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int purgeOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);
}
