package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.LoginHistoryEntity;

public interface LoginHistoryRepo
        extends JpaRepository<LoginHistoryEntity, Long>,
                JpaSpecificationExecutor<LoginHistoryEntity> {

    Optional<LoginHistoryEntity> findBySessionRowId(Long sessionRowId);

    @Query("SELECT COUNT(h) FROM LoginHistoryEntity h WHERE h.loginStatus = 'SUCCESS' AND h.loginAt >= :from AND h.loginAt < :to")
    long countSuccessBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(h) FROM LoginHistoryEntity h WHERE h.loginStatus <> 'SUCCESS' AND h.loginAt >= :from AND h.loginAt < :to")
    long countFailedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT h.userId) FROM LoginHistoryEntity h WHERE h.loginStatus = 'SUCCESS' AND h.loginAt >= :from AND h.loginAt < :to")
    long countUniqueUsersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT CONCAT(COALESCE(h.deviceType,''),'|',COALESCE(h.browser,''),'|',COALESCE(h.operatingSystem,''),'|',COALESCE(h.ipAddress,''))) "
         + "FROM LoginHistoryEntity h WHERE h.loginStatus = 'SUCCESS' AND h.loginAt >= :from AND h.loginAt < :to")
    long countUniqueDevicesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT h.city) FROM LoginHistoryEntity h WHERE h.city IS NOT NULL AND h.loginStatus = 'SUCCESS' AND h.loginAt >= :from AND h.loginAt < :to")
    long countUniqueCitiesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── User summary aggregations ─────────────────────────────────────────
    @Query("SELECT COUNT(h) FROM LoginHistoryEntity h WHERE h.userId = :userId AND h.loginStatus = 'SUCCESS'")
    long countLoginsByUser(@Param("userId") Long userId);

    @Query("SELECT AVG(h.sessionDurationSec) FROM LoginHistoryEntity h WHERE h.userId = :userId AND h.sessionDurationSec IS NOT NULL")
    Double avgSessionDurationByUser(@Param("userId") Long userId);

    @Query("SELECT h.deviceType, COUNT(h) AS c FROM LoginHistoryEntity h WHERE h.userId = :userId AND h.deviceType IS NOT NULL GROUP BY h.deviceType ORDER BY c DESC")
    List<Object[]> topDevicesByUser(@Param("userId") Long userId);

    @Query("SELECT h.browser, COUNT(h) AS c FROM LoginHistoryEntity h WHERE h.userId = :userId AND h.browser IS NOT NULL GROUP BY h.browser ORDER BY c DESC")
    List<Object[]> topBrowsersByUser(@Param("userId") Long userId);

    @Query("SELECT h.city, COUNT(h) AS c FROM LoginHistoryEntity h WHERE h.userId = :userId AND h.city IS NOT NULL GROUP BY h.city ORDER BY c DESC")
    List<Object[]> topCitiesByUser(@Param("userId") Long userId);

    // ── Trend for the dashboard chart ─────────────────────────────────────
    @Query("SELECT FUNCTION('DATE', h.loginAt), COUNT(h) FROM LoginHistoryEntity h "
         + "WHERE h.loginStatus = 'SUCCESS' AND h.loginAt >= :from GROUP BY FUNCTION('DATE', h.loginAt) ORDER BY 1")
    List<Object[]> loginTrendSince(@Param("from") LocalDateTime from);

    @Query("SELECT h.deviceType, COUNT(h) FROM LoginHistoryEntity h "
         + "WHERE h.loginStatus = 'SUCCESS' AND h.loginAt >= :from AND h.deviceType IS NOT NULL GROUP BY h.deviceType")
    List<Object[]> deviceSplitSince(@Param("from") LocalDateTime from);

    // ── Retention (see LogRetentionJob) ───────────────────────────────────
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM login_history WHERE login_status <> 'SUCCESS' AND login_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int deleteFailedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    @Modifying
    @Transactional
    @Query(value = "INSERT IGNORE INTO login_history_archive SELECT * FROM login_history WHERE login_status = 'SUCCESS' AND login_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int archiveOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM login_history WHERE login_status = 'SUCCESS' AND login_at < :cutoff ORDER BY id LIMIT :batch", nativeQuery = true)
    int deleteArchivedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /** Fills location on the user's most recent SUCCESS login if it was
        recorded before the browser permission was granted. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE login_history SET latitude = :lat, longitude = :lng, "
        + "city = :city, state = :state, country = :country "
        + "WHERE user_id = :userId AND login_status = 'SUCCESS' AND latitude IS NULL "
        + "ORDER BY id DESC LIMIT 1", nativeQuery = true)
    int fillMissingGeoForLatestLogin(@Param("userId") Long userId,
                                     @Param("lat") Double lat, @Param("lng") Double lng,
                                     @Param("city") String city, @Param("state") String state,
                                     @Param("country") String country);

    /** Most recent successful login — powers "Latest device/browser/location". */
    java.util.Optional<LoginHistoryEntity> findFirstByUserIdAndLoginStatusOrderByLoginAtDesc(
            Long userId, String loginStatus);
}
