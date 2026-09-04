package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;

public interface BorrowerSanctionRepo extends JpaRepository<BorrowerSanctionEntity, Long> {

    List<BorrowerSanctionEntity> findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(Long borrowerId);

    /**
     * Batched form of the single-borrower lookup above — loads every live
     * sanction for a whole set of borrowers in one query, so a caller that
     * needs this for many borrowers (a registry list, a hierarchy tree/stats
     * walk, a group's rollup) can group the results in memory afterwards
     * instead of querying once per borrower.
     */
    List<BorrowerSanctionEntity> findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(List<Long> borrowerIds);

    Optional<BorrowerSanctionEntity> findByIdAndDeletedAtIsNull(Long id);

    /** Sanctions associated directly with one Parent Group or Sub Group — never a child company's own sanctions. */
    List<BorrowerSanctionEntity> findByGroupIdAndDeletedAtIsNullOrderBySanctionDateDesc(Long groupId);

    /** Duplicate guard — the ref no. is what makes a letter unique. */
    Optional<BorrowerSanctionEntity> findByRefNoIgnoreCaseAndDeletedAtIsNull(String refNo);

    List<BorrowerSanctionEntity> findByDeletedAtIsNullOrderByCreatedAtDesc();

    /**
     * Fetch the document bytes on their own.
     *
     * <p>Loading the blob through the entity is a trap: as a plain @Lob it is
     * pulled into memory on every list query, and as @Basic(LAZY) it needs
     * bytecode enhancement and throws once the entity is detached. Selecting
     * the column directly avoids both — the bytes are read only when a user
     * actually opens or downloads the letter.
     */
    @Query("SELECT s.sanctionDocData FROM BorrowerSanctionEntity s "
         + "WHERE s.id = :id AND s.deletedAt IS NULL")
    byte[] findDocData(@Param("id") Long id);

    /** Distinct categories across all live sanctions, for the filter dropdown. */
    @Query("SELECT DISTINCT s.category FROM BorrowerSanctionEntity s "
         + "WHERE s.deletedAt IS NULL AND s.category IS NOT NULL AND s.category <> '' "
         + "ORDER BY s.category ASC")
    List<String> findDistinctCategories();
}