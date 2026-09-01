package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerEntity;

public interface BorrowerRepo extends JpaRepository<BorrowerEntity, Long> {

    List<BorrowerEntity> findByDeletedAtIsNullOrderByCreatedAtDesc();

    Optional<BorrowerEntity> findByIdAndDeletedAtIsNull(Long id);

    /** Every company sitting directly under one Parent Group or Sub Group. */
    List<BorrowerEntity> findByGroupId(Long groupId);

    /**
     * One page of the companies sitting directly under one group — serves
     * both a Parent Group's Direct Companies table and any Sub Group's own
     * inline table (called with that Sub Group's id). {@link #findByGroupId}
     * above stays unpaginated; it's used by the hard-delete cascade, which
     * needs every row, not one page of them.
     */
    Page<BorrowerEntity> findByGroupIdAndDeletedAtIsNullOrderByBorrowerNameAsc(Long groupId, Pageable pageable);

    /**
     * Same as above, restricted to Role Hierarchy scope (level 3/4 callers
     * only — see BorrowerService.inScope/borrowerInScope). The EXISTS clause
     * mirrors borrowerInScope's "visible via what's mine" rule: a borrower
     * whose OWN createdBy is out of scope still shows if the caller
     * personally has a sanction of their own attached under it (e.g.
     * attached via the always-global Company Match) — an in-memory filter
     * after this query's own LIMIT/OFFSET can't express that without
     * breaking pagination, so it has to live in the query itself.
     */
    @Query("SELECT b FROM BorrowerEntity b WHERE b.groupId = :groupId AND b.deletedAt IS NULL AND "
         + "(b.createdBy IN :scopeIds OR EXISTS (SELECT 1 FROM BorrowerSanctionEntity s "
         + "WHERE s.borrowerId = b.id AND s.deletedAt IS NULL AND s.createdBy IN :scopeIds)) "
         + "ORDER BY b.borrowerName ASC")
    Page<BorrowerEntity> findByGroupIdAndCreatedByInAndDeletedAtIsNullOrderByBorrowerNameAsc(
            @Param("groupId") Long groupId, @Param("scopeIds") List<Long> createdByIds, Pageable pageable);

    /** Companies sitting directly under one group — Group Detail's "Direct Companies" count. */
    long countByGroupIdAndDeletedAtIsNull(Long groupId);

    /** Same, restricted to Role Hierarchy scope (level 3/4 only) — same EXISTS rule as the paged query above. */
    @Query("SELECT COUNT(b) FROM BorrowerEntity b WHERE b.groupId = :groupId AND b.deletedAt IS NULL AND "
         + "(b.createdBy IN :scopeIds OR EXISTS (SELECT 1 FROM BorrowerSanctionEntity s "
         + "WHERE s.borrowerId = b.id AND s.deletedAt IS NULL AND s.createdBy IN :scopeIds))")
    long countByGroupIdAndCreatedByInAndDeletedAtIsNull(
            @Param("groupId") Long groupId, @Param("scopeIds") List<Long> createdByIds);

    /**
     * One page of standalone companies (no group at all) — the Level‑1
     * registry list's second "dimension", after top-level Parent Groups.
     * Same name/CIN predicate {@code HierarchyTree.js} already applied
     * client-side for a standalone row, just server-side and paginated now.
     * Plain LIMIT/OFFSET rather than a Spring Data {@code Pageable}: the
     * registry's Level‑1 list stitches this dimension together with the
     * (separately, in-memory paged) groups dimension into one virtual
     * sequence, so the offset into this query isn't always a multiple of
     * the page size.
     */
    @Query(value = "SELECT * FROM borrowers b WHERE b.group_id IS NULL AND b.deleted_at IS NULL"
         + " AND (:q IS NULL OR :q = ''"
         + "   OR LOWER(b.borrower_name) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.cin, '')) LIKE LOWER(CONCAT('%', :q, '%')))"
         + " ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<BorrowerEntity> findStandalonePage(@Param("q") String q, @Param("offset") int offset, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM borrowers b WHERE b.group_id IS NULL AND b.deleted_at IS NULL"
         + " AND (:q IS NULL OR :q = ''"
         + "   OR LOWER(b.borrower_name) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.cin, '')) LIKE LOWER(CONCAT('%', :q, '%')))", nativeQuery = true)
    long countStandalonePage(@Param("q") String q);

    /**
     * Same pair as findStandalonePage/countStandalonePage, restricted to
     * Role Hierarchy scope (level 3/4 only) — same "visible via what's
     * mine" EXISTS rule as findByGroupIdAndCreatedByIn... above.
     */
    @Query(value = "SELECT * FROM borrowers b WHERE b.group_id IS NULL AND b.deleted_at IS NULL"
         + " AND (:q IS NULL OR :q = ''"
         + "   OR LOWER(b.borrower_name) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.cin, '')) LIKE LOWER(CONCAT('%', :q, '%')))"
         + " AND (b.created_by IN (:scopeIds) OR EXISTS (SELECT 1 FROM borrower_sanctions s "
         + "WHERE s.borrower_id = b.id AND s.deleted_at IS NULL AND s.created_by IN (:scopeIds)))"
         + " ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<BorrowerEntity> findStandalonePageScoped(@Param("q") String q, @Param("scopeIds") List<Long> scopeIds,
            @Param("offset") int offset, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM borrowers b WHERE b.group_id IS NULL AND b.deleted_at IS NULL"
         + " AND (:q IS NULL OR :q = ''"
         + "   OR LOWER(b.borrower_name) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.cin, '')) LIKE LOWER(CONCAT('%', :q, '%')))"
         + " AND (b.created_by IN (:scopeIds) OR EXISTS (SELECT 1 FROM borrower_sanctions s "
         + "WHERE s.borrower_id = b.id AND s.deleted_at IS NULL AND s.created_by IN (:scopeIds)))", nativeQuery = true)
    long countStandalonePageScoped(@Param("q") String q, @Param("scopeIds") List<Long> scopeIds);

    /**
     * Every company under one group's own hierarchy — its direct companies
     * plus every one of its Sub Groups' direct companies (one level, same
     * cap the service layer already enforces on {@code company_groups}).
     * Backs Group Detail's "Total Companies" / "Total SPVs" stat cards,
     * which need a hierarchy-wide count independent of whichever page of
     * Direct Companies or Sub Groups happens to be loaded.
     */
    @Query("SELECT COUNT(b) FROM BorrowerEntity b WHERE b.deletedAt IS NULL AND "
         + "(b.groupId = :groupId OR b.groupId IN "
         + "(SELECT g.id FROM CompanyGroupEntity g WHERE g.parentGroupId = :groupId AND g.deletedAt IS NULL))")
    long countCompaniesUnderGroup(@Param("groupId") Long groupId);

    @Query("SELECT COUNT(b) FROM BorrowerEntity b WHERE b.deletedAt IS NULL AND b.isSpv = true AND "
         + "(b.groupId = :groupId OR b.groupId IN "
         + "(SELECT g.id FROM CompanyGroupEntity g WHERE g.parentGroupId = :groupId AND g.deletedAt IS NULL))")
    long countSpvsUnderGroup(@Param("groupId") Long groupId);

    /** Same pair as above, restricted to Role Hierarchy scope (level 3/4 only) — same "visible via what's mine" EXISTS rule. */
    @Query("SELECT COUNT(b) FROM BorrowerEntity b WHERE b.deletedAt IS NULL AND "
         + "(b.createdBy IN :scopeIds OR EXISTS (SELECT 1 FROM BorrowerSanctionEntity s "
         + "WHERE s.borrowerId = b.id AND s.deletedAt IS NULL AND s.createdBy IN :scopeIds)) AND "
         + "(b.groupId = :groupId OR b.groupId IN "
         + "(SELECT g.id FROM CompanyGroupEntity g WHERE g.parentGroupId = :groupId AND g.deletedAt IS NULL))")
    long countCompaniesUnderGroupScoped(@Param("groupId") Long groupId, @Param("scopeIds") List<Long> scopeIds);

    @Query("SELECT COUNT(b) FROM BorrowerEntity b WHERE b.deletedAt IS NULL AND b.isSpv = true AND "
         + "(b.createdBy IN :scopeIds OR EXISTS (SELECT 1 FROM BorrowerSanctionEntity s "
         + "WHERE s.borrowerId = b.id AND s.deletedAt IS NULL AND s.createdBy IN :scopeIds)) AND "
         + "(b.groupId = :groupId OR b.groupId IN "
         + "(SELECT g.id FROM CompanyGroupEntity g WHERE g.parentGroupId = :groupId AND g.deletedAt IS NULL))")
    long countSpvsUnderGroupScoped(@Param("groupId") Long groupId, @Param("scopeIds") List<Long> scopeIds);

    Optional<BorrowerEntity> findByCinIgnoreCaseAndDeletedAtIsNull(String cin);

    /**
     * Exact-name lookup used by the importer before creating a borrower.
     * Deliberately not fuzzy: a near-match should surface as a choice for the
     * user in the review screen, not be silently merged here.
     */
    Optional<BorrowerEntity> findByBorrowerNameIgnoreCaseAndDeletedAtIsNull(String borrowerName);

    /**
     * Registry search. Covers what the search box actually promises — borrower
     * name, CIN, PAN, promoter, group <em>and</em> the sanction reference
     * number, which the registry sheet prints as "SL Ref. No". The original
     * version omitted ref no., so pasting one returned nothing even though the
     * placeholder invited it; promoter and group joined the list when they
     * became visible columns.
     *
     * <p>Both parameters are optional and combine: a null or blank value means
     * "don't filter on this", so one query serves all four states.
     */
    @Query("SELECT b FROM BorrowerEntity b WHERE b.deletedAt IS NULL"
         + " AND (:q IS NULL OR :q = '' OR"
         + "   LOWER(b.borrowerName) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.cin, '')) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.pan, '')) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.promoterName, '')) LIKE LOWER(CONCAT('%', :q, '%'))"
         + "   OR LOWER(COALESCE(b.groupName, '')) LIKE LOWER(CONCAT('%', :q, '%'))"
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