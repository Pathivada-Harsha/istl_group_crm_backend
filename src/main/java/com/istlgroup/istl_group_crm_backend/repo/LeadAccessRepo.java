package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.LeadAccessEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface LeadAccessRepo extends JpaRepository<LeadAccessEntity, Long> {

    List<LeadAccessEntity> findByLeadId(Long leadId);
    List<LeadAccessEntity> findByUserId(Long userId);
    Optional<LeadAccessEntity> findByLeadIdAndUserId(Long leadId, Long userId);

    @Query("SELECT a FROM LeadAccessEntity a WHERE a.leadId = :leadId AND a.role = :role")
    List<LeadAccessEntity> findByLeadIdAndRole(
            @Param("leadId") Long leadId, @Param("role") String role);

    boolean existsByLeadIdAndUserId(Long leadId, Long userId);
}