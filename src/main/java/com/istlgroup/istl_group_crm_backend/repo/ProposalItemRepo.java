package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.istlgroup.istl_group_crm_backend.entity.ProposalItemEntity;

public interface ProposalItemRepo extends JpaRepository<ProposalItemEntity, Long> {
    
    List<ProposalItemEntity> findByProposalIdOrderByLineNo(Long proposalId);
    
    void deleteByProposalId(Long proposalId);
    
    @Query(value = "SELECT *, quantity * unit_price AS line_total " +
           "FROM proposal_items WHERE proposal_id = :proposalId ORDER BY line_no",
           nativeQuery = true)
    List<ProposalItemEntity> findByProposalIdWithCalculatedFields(@Param("proposalId") Long proposalId);
}