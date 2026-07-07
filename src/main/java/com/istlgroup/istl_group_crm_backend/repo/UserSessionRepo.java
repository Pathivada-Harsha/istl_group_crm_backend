package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.UserSessionEntity;

import jakarta.persistence.LockModeType;

public interface UserSessionRepo extends JpaRepository<UserSessionEntity, Long> {

    /**
     * Locks this user's ACTIVE session rows for the duration of the login
     * transaction (SELECT ... FOR UPDATE). Two simultaneous logins for the
     * same user are serialized by MySQL, which is what prevents the
     * "3 devices survive a race" problem.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSessionEntity s WHERE s.userId = :userId AND s.status = 'ACTIVE' ORDER BY s.loginAt ASC")
    List<UserSessionEntity> findActiveByUserIdForUpdate(@Param("userId") Long userId);

    Optional<UserSessionEntity> findBySessionHash(String sessionHash);

    @Query("SELECT s FROM UserSessionEntity s WHERE s.status = 'ACTIVE' ORDER BY s.lastSeenAt DESC")
    List<UserSessionEntity> findAllActive();

    @Query("SELECT s FROM UserSessionEntity s WHERE s.userId = :userId AND s.status = 'ACTIVE' ORDER BY s.lastSeenAt DESC")
    List<UserSessionEntity> findActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(s) FROM UserSessionEntity s WHERE s.status = 'ACTIVE'")
    long countActive();

    /** Stale ACTIVE sessions whose last activity is older than the cutoff. */
    @Query("SELECT s FROM UserSessionEntity s WHERE s.status = 'ACTIVE' AND s.lastSeenAt < :cutoff")
    List<UserSessionEntity> findStaleActive(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query("UPDATE UserSessionEntity s SET s.lastSeenAt = :seenAt WHERE s.sessionHash = :hash AND s.status = 'ACTIVE'")
    int touch(@Param("hash") String hash, @Param("seenAt") LocalDateTime seenAt);

    /** Late geo fill once the user grants location after login. */
    @Modifying
    @Transactional
    @Query("UPDATE UserSessionEntity s SET s.latitude = :lat, s.longitude = :lng, "
         + "s.city = :city, s.state = :state, s.country = :country "
         + "WHERE s.sessionHash = :hash AND s.status = 'ACTIVE'")
    int updateGeo(@Param("hash") String hash, @Param("lat") Double lat, @Param("lng") Double lng,
                  @Param("city") String city, @Param("state") String state, @Param("country") String country);
}
