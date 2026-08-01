// ─────────────────────────────────────────────────────────────────────────────
// PROVISIONAL FEATURE — "Orders in Line"
// Temporary stopgap register, scheduled for replacement by a permanent pipeline
// module. Data here migrates into the leads table at that point.
// Removal: drop table `orders_in_line`, delete the OrdersInLine* files, revert the
// two lines in Dashboard.js, the sidebar entry, and the App.js import + route.
// ─────────────────────────────────────────────────────────────────────────────
package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.OrdersInLineEntity;

public interface OrdersInLineRepo extends JpaRepository<OrdersInLineEntity, Long> {

    Optional<OrdersInLineEntity> findByIdAndDeletedAtIsNull(Long id);

    /** Every live row — used by the dashboard summary aggregate. */
    List<OrdersInLineEntity> findByDeletedAtIsNull();

    /**
     * Filtered list. Uses the house {@code :param IS NULL OR …} JPQL pattern
     * (see LeadsRepo#searchLeadsPaged) — filters combine with AND and an absent
     * filter arrives as null and is ignored. The service blanks-to-null before
     * calling, so an empty search box is not treated as a filter.
     */
    @Query("SELECT o FROM OrdersInLineEntity o WHERE o.deletedAt IS NULL "
         + "AND (:search IS NULL OR LOWER(o.clientName)    LIKE LOWER(CONCAT('%', :search, '%')) "
         + "                     OR LOWER(o.sourceParty)   LIKE LOWER(CONCAT('%', :search, '%')) "
         + "                     OR LOWER(o.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))) "
         + "AND (:status   IS NULL OR o.status   = :status) "
         + "AND (:category IS NULL OR o.category = :category) "
         + "AND (:fromDate IS NULL OR o.receivedDate >= :fromDate) "
         + "AND (:toDate   IS NULL OR o.receivedDate <= :toDate) "
         + "ORDER BY o.receivedDate DESC, o.id DESC")
    List<OrdersInLineEntity> search(@Param("search")   String search,
                                    @Param("status")   String status,
                                    @Param("category") String category,
                                    @Param("fromDate") LocalDate fromDate,
                                    @Param("toDate")   LocalDate toDate);
}
