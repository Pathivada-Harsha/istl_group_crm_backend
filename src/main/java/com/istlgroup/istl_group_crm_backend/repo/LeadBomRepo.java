package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;

public interface LeadBomRepo extends JpaRepository<LeadBomEntity, Long> {

    List<LeadBomEntity> findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long leadId);

    /** A past lead that has a BOM, with its capacity — one row per such lead. */
    interface MinedLeadCapacity {
        Long getLeadId();
        String getCapacity();
        String getCapacityUnit();
    }

    /**
     * Mining candidates: every OTHER lead of the same project type that has at
     * least one live BOM line, with that lead's raw capacity. The service parses
     * and normalises the capacity, keeps the same unit-family, and picks the one
     * nearest the target. Distinct-by-lead via GROUP BY.
     */
    @Query("SELECT b.leadId AS leadId, l.capacity AS capacity, l.capacityUnit AS capacityUnit "
         + "FROM LeadBomEntity b, LeadsEntity l "
         + "WHERE l.id = b.leadId "
         + "  AND b.deletedAt IS NULL "
         + "  AND l.subGroupName = :projectType "
         + "  AND b.leadId <> :excludeLeadId "
         + "GROUP BY b.leadId, l.capacity, l.capacityUnit")
    List<MinedLeadCapacity> findMiningCandidates(@Param("projectType") String projectType,
                                                 @Param("excludeLeadId") Long excludeLeadId);
}
