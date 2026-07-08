package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryArchiveEntity;

public interface LoginHistoryArchiveRepo
        extends JpaRepository<LoginHistoryArchiveEntity, Long>,
                JpaSpecificationExecutor<LoginHistoryArchiveEntity> {

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM login_history_archive WHERE login_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int purgeOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);
}
