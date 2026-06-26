package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemEntity;

public interface OrderBookItemRepo extends JpaRepository<OrderBookItemEntity, Long> {

    List<OrderBookItemEntity> findByOrderBookIdOrderByLineNo(Long orderBookId);

    void deleteByOrderBookId(Long orderBookId);

    @Query("SELECT i FROM OrderBookItemEntity i WHERE i.orderBookId = :orderBookId ORDER BY i.lineNo")
    List<OrderBookItemEntity> findByOrderBookIdWithCalculatedFields(@Param("orderBookId") Long orderBookId);

    /**
     * Per-order-book capacity for the modal detail view.
     * Returns rows: [projectId, orderBookNo, orderTitle, unit, SUM(quantity), customerName]
     * One row per order book per unit — so projects with multiple order books show each separately.
     */
    @Query(value =
        "SELECT ob.project_id, ob.order_book_no, ob.order_title, i.unit, SUM(i.quantity) AS total_qty, " +
        "       COALESCE(c.company_name, c.name, '') AS customer_name " +
        "FROM order_book_items i " +
        "JOIN order_book ob ON ob.id = i.order_book_id " +
        "LEFT JOIN customers c ON c.id = ob.customer_id " +
        "WHERE ob.deleted_at IS NULL " +
        "  AND (:groupName IS NULL OR ob.group_name = :groupName) " +
        "  AND ob.sub_group_name = :subGroupName " +
        "GROUP BY ob.project_id, ob.order_book_no, ob.order_title, i.unit, c.company_name, c.name " +
        "ORDER BY ob.project_id, ob.order_book_no, i.unit",
        nativeQuery = true)
    List<Object[]> findOrderBookCapacityBySubGroup(@Param("groupName") String groupName,
                                                    @Param("subGroupName") String subGroupName);

    /**
     * All unit totals for a single project — used for the capacity modal breakdown.
     * Returns rows: [unit, SUM(quantity)]
     */
    @Query(value =
        "SELECT i.unit, SUM(i.quantity) AS total_qty " +
        "FROM order_book_items i " +
        "JOIN order_book ob ON ob.id = i.order_book_id " +
        "WHERE ob.deleted_at IS NULL " +
        "  AND ob.project_id = :projectId " +
        "GROUP BY i.unit " +
        "ORDER BY total_qty DESC",
        nativeQuery = true)
    List<Object[]> findUnitBreakdownByProject(@Param("projectId") String projectId);

    /**
     * Aggregated unit totals across all order books in a subgroup — for the card display.
     * Returns rows: [unit, SUM(quantity)]
     */
    @Query(value =
        "SELECT i.unit, SUM(i.quantity) AS total_qty " +
        "FROM order_book_items i " +
        "JOIN order_book ob ON ob.id = i.order_book_id " +
        "WHERE ob.deleted_at IS NULL " +
        "  AND (:groupName IS NULL OR ob.group_name = :groupName) " +
        "  AND ob.sub_group_name = :subGroupName " +
        "GROUP BY i.unit " +
        "ORDER BY total_qty DESC",
        nativeQuery = true)
    List<Object[]> findUnitTotalsBySubGroup(@Param("groupName") String groupName,
                                             @Param("subGroupName") String subGroupName);
}