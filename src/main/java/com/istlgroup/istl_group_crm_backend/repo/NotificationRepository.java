package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    // ── Navbar dropdown: latest 10 for a user ────────────────────────
    List<NotificationEntity> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    // ── Unread badge count (indexed) ─────────────────────────────────
    long countByUserIdAndIsReadFalse(Long userId);

    // ── Notification page: paged + search + read/unread filter ───────
    // readFilter: null = ALL, true = READ only, false = UNREAD only
    @Query("SELECT n FROM NotificationEntity n " +
           "WHERE n.userId = :userId " +
           "  AND (:readFilter IS NULL OR n.isRead = :readFilter) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(n.title)   LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "       LOWER(n.message) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<NotificationEntity> findForUser(@Param("userId") Long userId,
                                         @Param("readFilter") Boolean readFilter,
                                         @Param("search") String search,
                                         Pageable pageable);

    // ── Mark all as read for a user (single UPDATE) ──────────────────
    @Modifying
    @Transactional
    @Query("UPDATE NotificationEntity n SET n.isRead = true, n.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE n.userId = :userId AND n.isRead = false")
    int markAllReadForUser(@Param("userId") Long userId);

    // ── Duplicate guard for the scheduler ────────────────────────────
    // "Has this exact event already been raised for this record since :since?"
    boolean existsByUserIdAndNotificationTypeAndReferenceIdAndCreatedAtAfter(
            Long userId, String notificationType, Long referenceId, LocalDateTime since);
}
