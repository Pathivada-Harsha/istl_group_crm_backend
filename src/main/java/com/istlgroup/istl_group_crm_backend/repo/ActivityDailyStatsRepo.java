package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.ActivityDailyStatsEntity;

public interface ActivityDailyStatsRepo extends JpaRepository<ActivityDailyStatsEntity, LocalDate> {

    @Query("SELECT s FROM ActivityDailyStatsEntity s WHERE s.statDate >= :from ORDER BY s.statDate")
    List<ActivityDailyStatsEntity> findSince(@Param("from") LocalDate from);
}
