package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.SiteVisitEntity;

public interface SiteVisitRepo extends JpaRepository<SiteVisitEntity, Long> {

    /** All visits for a lead, newest visit first (falls back to created order). */
    List<SiteVisitEntity> findByLeadIdOrderByVisitDateDescIdDesc(Long leadId);
}
