package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.CompanyGroupEntity;

public interface CompanyGroupRepo extends JpaRepository<CompanyGroupEntity, Long> {

    Optional<CompanyGroupEntity> findByIdAndDeletedAtIsNull(Long id);

    /** Top-level Parent Groups. */
    List<CompanyGroupEntity> findByParentGroupIdIsNullAndDeletedAtIsNullOrderByGroupNameAsc();

    /** Sub Groups under a given Parent Group. */
    List<CompanyGroupEntity> findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(Long parentGroupId);

    /** One page of the Sub Groups under a given Parent Group — for the paginated Sub Groups list on Group Detail. */
    Page<CompanyGroupEntity> findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(Long parentGroupId, Pageable pageable);

    /**
     * Same, restricted to Role Hierarchy scope (level 3/4 only — see
     * BorrowerService.inScope/groupInScope). Mirrors groupInScope's rule: a
     * Sub Group whose own createdBy is out of scope still shows if it
     * contains at least one company visible under the same "visible via
     * what's mine" rule (own createdBy, or a sanction the caller personally
     * attached — see BorrowerService.borrowerInScope).
     */
    @Query("SELECT g FROM CompanyGroupEntity g WHERE g.parentGroupId = :parentGroupId AND g.deletedAt IS NULL AND "
         + "(g.createdBy IN :scopeIds OR EXISTS (SELECT 1 FROM BorrowerEntity b WHERE b.groupId = g.id "
         + "AND b.deletedAt IS NULL AND (b.createdBy IN :scopeIds OR EXISTS "
         + "(SELECT 1 FROM BorrowerSanctionEntity s WHERE s.borrowerId = b.id AND s.deletedAt IS NULL "
         + "AND s.createdBy IN :scopeIds)))) "
         + "ORDER BY g.groupName ASC")
    Page<CompanyGroupEntity> findByParentGroupIdAndCreatedByInAndDeletedAtIsNullOrderByGroupNameAsc(
            @Param("parentGroupId") Long parentGroupId, @Param("scopeIds") List<Long> createdByIds, Pageable pageable);

    List<CompanyGroupEntity> findByDeletedAtIsNullOrderByGroupNameAsc();

    @Query("SELECT g FROM CompanyGroupEntity g WHERE g.deletedAt IS NULL"
         + " AND LOWER(g.groupName) LIKE LOWER(CONCAT('%', :q, '%'))"
         + " ORDER BY g.groupName ASC")
    List<CompanyGroupEntity> search(@Param("q") String q);

    /**
     * Exact case-insensitive name match, scoped to a parent (or to the
     * top-level groups when {@code parentGroupId} is null). Deliberately not
     * fuzzy, same reasoning as {@code BorrowerRepo.findByBorrowerNameIgnoreCase...}
     * — a near-match should be a choice for the user, not silently merged.
     */
    @Query("SELECT g FROM CompanyGroupEntity g WHERE g.deletedAt IS NULL"
         + " AND LOWER(g.groupName) = LOWER(:name)"
         + " AND ((:parentGroupId IS NULL AND g.parentGroupId IS NULL)"
         + "      OR g.parentGroupId = :parentGroupId)")
    Optional<CompanyGroupEntity> findByExactNameAndParent(@Param("name") String name,
                                                            @Param("parentGroupId") Long parentGroupId);
}
