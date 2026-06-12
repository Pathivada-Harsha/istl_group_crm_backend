package com.istlgroup.istl_group_crm_backend.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.UserProfileImageEntity;

@Repository
public interface UserProfileImageRepo extends JpaRepository<UserProfileImageEntity, Long> {

    Optional<UserProfileImageEntity> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserProfileImageEntity u WHERE u.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}