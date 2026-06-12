package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemCatalogueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderBookItemCatalogueRepo extends JpaRepository<OrderBookItemCatalogueEntity, Long> {

    /**
     * Case-insensitive name lookup — used when auto-saving after order book save
     * to decide insert vs update.
     */
    Optional<OrderBookItemCatalogueEntity> findByItemNameIgnoreCase(String itemName);

    /**
     * Search by name prefix/contains — used by the autocomplete endpoint.
     * Returns up to 10 results ordered by name for a snappy dropdown.
     */
    @Query("""
        SELECT c FROM OrderBookItemCatalogueEntity c
        WHERE LOWER(c.itemName) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY c.itemName ASC
        LIMIT 10
        """)
    List<OrderBookItemCatalogueEntity> searchByName(@Param("q") String q);
}