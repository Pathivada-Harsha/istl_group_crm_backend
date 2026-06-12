package com.istlgroup.istl_group_crm_backend.repo;
 
import com.istlgroup.istl_group_crm_backend.entity.VendorAdvanceAllocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
 
import java.util.List;
import java.util.Optional;
 
@Repository
public interface VendorAdvanceAllocationRepository extends JpaRepository<VendorAdvanceAllocationEntity, Long> {
 
    List<VendorAdvanceAllocationEntity> findByAdvance_IdOrderByAllocationDateDesc(Long advanceId);
 
    List<VendorAdvanceAllocationEntity> findByBillIdOrderByAllocationDateDesc(Long billId);
 
    Optional<VendorAdvanceAllocationEntity> findByAdvance_IdAndBillId(Long advanceId, Long billId);
 
    List<VendorAdvanceAllocationEntity> findByAdvance_Id(Long advanceId);
 
    @Modifying
    @Query("DELETE FROM VendorAdvanceAllocationEntity a WHERE a.advance.id = :advanceId AND a.billId = :billId")
    void deleteByAdvanceIdAndBillId(@Param("advanceId") Long advanceId, @Param("billId") Long billId);
}
 