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

    // Deletes are soft (deleted_at), so every read below excludes dismissed
    // rows. The scheduler's duplicate guard at the bottom deliberately does
    // not — see the comment there.

    // ── Navbar dropdown: latest 10 for a user ────────────────────────
    List<NotificationEntity> findTop10ByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    // ── Unread badge count (indexed) ─────────────────────────────────
    long countByUserIdAndIsReadFalseAndDeletedAtIsNull(Long userId);

    // ── Notification page: paged + search + read/unread filter ───────
    // readFilter: null = ALL, true = READ only, false = UNREAD only
    @Query("SELECT n FROM NotificationEntity n " +
           "WHERE n.userId = :userId " +
           "  AND n.deletedAt IS NULL " +
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
           "WHERE n.userId = :userId AND n.isRead = false AND n.deletedAt IS NULL")
    int markAllReadForUser(@Param("userId") Long userId);

    // ── Duplicate guard for the scheduler ────────────────────────────
    // "Has this exact event already been raised for this record since :since?"
    //
    // Intentionally counts dismissed (soft-deleted) rows too: a user who
    // deleted today's "Task overdue" has been told, and the 5-minute job must
    // not hand it straight back. A hard delete here is what made deleting
    // scheduler-generated notifications look like a no-op.
    boolean existsByUserIdAndNotificationTypeAndReferenceIdAndCreatedAtAfter(
            Long userId, String notificationType, Long referenceId, LocalDateTime since);
}
