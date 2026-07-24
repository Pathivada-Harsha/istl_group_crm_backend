package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerEntity;

public interface BorrowerRepo extends JpaRepository<BorrowerEntity, Long> {

    List<BorrowerEntity> findByDeletedAtIsNullOrderByCreatedAtDesc();

    Optional<BorrowerEntity> findByIdAndDeletedAtIsNull(Long id);

    Optional<BorrowerEntity> findByCinIgnoreCaseAndDeletedAtIsNull(String cin);

    /**
     * Exact-name lookup used by the importer before creating a borrower.
     * Deliberately not fuzzy: a near-match should surface as a choice for the
     * user in the review screen, not be silently merged here.
     */
    Optional<BorrowerEntity> findByBorrowerNameIgnoreCaseAndDeletedAtIsNull(String borrowerName);

    /**
     * Registry search. Covers what the search box actually promises — borrower
     * name, CIN, PAN <em>and</em> the sanction reference number. The original
     * version omitted ref no., so pasting one returned nothing even though the
     * placeholder invited it.
     *
     * <p>Both parameters are optional and combine: a null or blank value means
     * "don't filter on this", so one query serves all four states.
     */
    @Query("SELECT b FROM BorrowerEntity b WHERE b.deletedAt IS NULL"
         + " AND (:q IS NULL OR :q = '' OR"
         + "   LOWER(b.borrowerName) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.cin, '')) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.pan, '')) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR EXISTS (SELECT 1 FROM BorrowerSanctionEntity s"
         + "              WHERE s.borrowerId = b.id AND s.deletedAt IS NULL"
         + "              AND LOWER(s.refNo) LIKE LOWER(CONCAT('%', :q, '%')))"
         + " )"
         // Category lives on the sanction, not the borrower, so this matches a
         // borrower with ANY sanction in that category — not just the latest.
         + " AND (:category IS NULL OR :category = '' OR"
         + "   EXISTS (SELECT 1 FROM BorrowerSanctionEntity s2"
         + "           WHERE s2.borrowerId = b.id AND s2.deletedAt IS NULL"
         + "           AND s2.category = :category)"
         + " )"
         + " ORDER BY b.createdAt DESC")
    List<BorrowerEntity> search(@Param("q") String q, @Param("category") String category);
}