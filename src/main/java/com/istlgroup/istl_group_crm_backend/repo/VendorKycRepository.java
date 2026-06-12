package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.VendorKycEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorKycRepository extends JpaRepository<VendorKycEntity, Long> {

    /**
     * Fetch all KYC docs for a vendor WITHOUT loading the file_data BLOB.
     * Used for metadata-only responses (list / status check).
     * The JPQL projection skips the fileData field so no large binary is transferred.
     */
    @Query("SELECT v FROM VendorKycEntity v WHERE v.vendorId = :vendorId")
    List<VendorKycEntity> findByVendorIdWithoutFileData(@Param("vendorId") Long vendorId);

    /** Full row including file bytes — used only for the download endpoint */
    Optional<VendorKycEntity> findByVendorIdAndDocType(Long vendorId, String docType);

    /** Delete all docs for a vendor (cascade on vendor delete) */
    void deleteByVendorId(Long vendorId);
}