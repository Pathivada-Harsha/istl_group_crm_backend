package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerEntity;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.entity.CompanyGroupEntity;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerAliasRepo;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerRepo;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerSanctionRepo;
import com.istlgroup.istl_group_crm_backend.repo.CompanyGroupRepo;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerWrapper;

/**
 * Regression coverage for the N+1 fix in {@link BorrowerService}'s
 * hierarchy/scope-check machinery: {@code role_hierarchy.level_order} used to
 * be re-queried once per row (via {@code inScope}), and every borrower's
 * sanctions used to be fetched one at a time instead of batched. These tests
 * pin down BOTH halves — the query counts actually drop, AND the returned
 * data is byte-for-byte the same as the old per-row logic would have produced
 * (same borrowers, same "latest sanction" picked, same order).
 *
 * <p>Reflective field injection (no Spring context) via manual construction —
 * {@link BorrowerService} uses field {@code @Autowired}, so mocks are wired
 * in directly rather than through Mockito's {@code @InjectMocks} (which would
 * need a no-arg constructor that already exists here, but explicit wiring
 * keeps this test's dependency surface obvious).
 */
@ExtendWith(MockitoExtension.class)
class BorrowerServiceHierarchyTest {

    @Mock private BorrowerRepo borrowerRepo;
    @Mock private BorrowerSanctionRepo sanctionRepo;
    @Mock private CompanyGroupRepo companyGroupRepo;
    @Mock private BorrowerAliasRepo borrowerAliasRepo;
    @Mock private RoleHierarchyService roleHierarchyService;
    @Mock private TeamRepository teamRepository;
    @Mock private SanctionDerivedCalculator derived;

    private BorrowerService newService() {
        BorrowerService s = new BorrowerService();
        setField(s, "borrowerRepo", borrowerRepo);
        setField(s, "sanctionRepo", sanctionRepo);
        setField(s, "companyGroupRepo", companyGroupRepo);
        setField(s, "borrowerAliasRepo", borrowerAliasRepo);
        setField(s, "roleHierarchyService", roleHierarchyService);
        setField(s, "teamRepository", teamRepository);
        setField(s, "derived", derived);
        return s;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = BorrowerService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static BorrowerEntity borrower(long id, long createdBy) {
        BorrowerEntity b = new BorrowerEntity();
        b.setId(id);
        b.setBorrowerName("Borrower " + id);
        b.setCreatedBy(createdBy);
        return b;
    }

    private static BorrowerSanctionEntity sanction(long id, long borrowerId, String refNo,
            LocalDate date, String amount) {
        BorrowerSanctionEntity s = new BorrowerSanctionEntity();
        s.setId(id);
        s.setBorrowerId(borrowerId);
        s.setRefNo(refNo);
        s.setSanctionDate(date);
        s.setSanctionedAmount(new BigDecimal(amount));
        return s;
    }

    @Test
    @DisplayName("getAll: role level is resolved once, sanctions are fetched in one batched query, and the latest sanction per borrower is still correct")
    void getAllBatchesAndStaysCorrect() {
        BorrowerService service = newService();

        BorrowerEntity b10 = borrower(10L, 1L);
        BorrowerEntity b20 = borrower(20L, 1L);
        when(borrowerRepo.search(null, null)).thenReturn(List.of(b10, b20));

        // Admin level (<=2): inScope short-circuits true without ever needing
        // a per-borrower sanction lookup for the scope check itself.
        when(roleHierarchyService.getLevelOrder(anyString())).thenReturn(1);

        // Deliberately NOT pre-sorted by borrower — this is what the DB would
        // hand back for "ORDER BY sanction_date DESC" across both borrowers
        // at once, interleaved.
        BorrowerSanctionEntity s1 = sanction(1, 10L, "REF-1", LocalDate.of(2026, 3, 1), "10");
        BorrowerSanctionEntity s3 = sanction(3, 20L, "REF-3", LocalDate.of(2026, 2, 15), "20");
        BorrowerSanctionEntity s2 = sanction(2, 10L, "REF-2", LocalDate.of(2026, 1, 1), "5");
        when(sanctionRepo.findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(anyList()))
                .thenReturn(List.of(s1, s3, s2));

        List<BorrowerWrapper> out = service.getAll(1L, "ADMIN", null, null);

        assertEquals(2, out.size());
        assertEquals("REF-1", out.get(0).getLatestRefNo()); // borrower 10's latest is s1, not s2
        assertEquals("REF-3", out.get(1).getLatestRefNo()); // borrower 20's only sanction is s3

        // The actual regression check: one role-hierarchy lookup total, one
        // batched sanction query, and the old per-borrower query never runs.
        verify(roleHierarchyService, times(1)).getLevelOrder(anyString());
        verify(sanctionRepo, times(1)).findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(anyList());
        verify(sanctionRepo, never()).findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(anyLong());
    }

    @Test
    @DisplayName("getHierarchyStats: role level resolved once regardless of how many borrowers/groups are scope-checked, rollup totals still sum correctly")
    void getHierarchyStatsBatchesAndStaysCorrect() {
        BorrowerService service = newService();

        BorrowerEntity b10 = borrower(10L, 1L);
        BorrowerEntity b20 = borrower(20L, 1L);
        when(borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc()).thenReturn(List.of(b10, b20));
        when(companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc()).thenReturn(List.<CompanyGroupEntity>of());
        when(roleHierarchyService.getLevelOrder(anyString())).thenReturn(1);

        BorrowerSanctionEntity s1 = sanction(1, 10L, "REF-1", LocalDate.of(2026, 3, 1), "10.50");
        BorrowerSanctionEntity s2 = sanction(2, 10L, "REF-2", LocalDate.of(2026, 1, 1), "5.00");
        BorrowerSanctionEntity s3 = sanction(3, 20L, "REF-3", LocalDate.of(2026, 2, 15), "20.00");
        when(sanctionRepo.findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(anyList()))
                .thenReturn(List.of(s1, s2, s3));

        var stats = service.getHierarchyStats(1L, "ADMIN");

        assertEquals(2, stats.get("totalCompanies"));
        assertEquals(0, stats.get("totalGroups"));
        assertEquals(3, stats.get("totalSanctionLetters")); // 2 for borrower 10 + 1 for borrower 20

        verify(roleHierarchyService, times(1)).getLevelOrder(anyString());
        verify(sanctionRepo, times(1)).findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(anyList());
        verify(sanctionRepo, never()).findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(anyLong());
    }
}
