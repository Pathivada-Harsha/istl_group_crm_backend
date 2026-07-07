package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;

public interface AuditLogRepo
        extends JpaRepository<AuditLogEntity, Long>,
                JpaSpecificationExecutor<AuditLogEntity> {

    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.createdAt >= :from AND a.createdAt < :to")
    long countBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT a.userId, a.username, COUNT(a) AS c FROM AuditLogEntity a "
         + "WHERE a.createdAt >= :from AND a.createdAt < :to AND a.userId IS NOT NULL "
         + "GROUP BY a.userId, a.username ORDER BY c DESC")
    List<Object[]> mostActiveUsersBetween(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          Pageable pageable);

    @Query("SELECT a FROM AuditLogEntity a WHERE a.userId = :userId ORDER BY a.createdAt DESC")
    List<AuditLogEntity> recentByUser(@Param("userId") Long userId, Pageable pageable);

    // ── Retention (see LogRetentionJob) ───────────────────────────────────
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM audit_logs_v2 WHERE operation = 'VIEW' AND created_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int deletePageViewsOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    @Modifying
    @Transactional
    @Query(value = "INSERT IGNORE INTO audit_logs_v2_archive SELECT * FROM audit_logs_v2 WHERE created_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int archiveOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM audit_logs_v2 WHERE created_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int deleteArchivedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);
}
