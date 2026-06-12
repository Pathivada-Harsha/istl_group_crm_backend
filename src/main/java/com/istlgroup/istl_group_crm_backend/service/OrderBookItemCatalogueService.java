package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemCatalogueEntity;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookItemCatalogueRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemCatalogueWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemRequestWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OrderBookItemCatalogueService {

    @Autowired
    private OrderBookItemCatalogueRepo catalogueRepo;

    /**
     * Autocomplete search — called by the frontend as the user types.
     * Returns up to 10 matching catalogue items.
     */
    public List<OrderBookItemCatalogueWrapper> search(String query) {
        if (query == null || query.trim().length() < 2) return List.of();
        return catalogueRepo.searchByName(query.trim())
                .stream()
                .map(OrderBookItemCatalogueWrapper::from)
                .collect(Collectors.toList());
    }

    /**
     * Upsert logic called after every order book create/update.
     *
     * Strategy:
     *  - If item name doesn't exist in catalogue → INSERT it.
     *  - If it already exists → UPDATE specification, description, unit.
     *    Unit price, tax%, discount% are NOT overwritten — rates vary per order
     *    and overwriting would give future users misleading autofill values.
     *    Only structural/descriptive fields are refreshed.
     *
     * This is intentionally fire-and-forget (no exception propagation)
     * so a catalogue failure never blocks an order book save.
     */
    @Transactional
    public void upsertFromItems(List<OrderBookItemRequestWrapper> items) {
        if (items == null || items.isEmpty()) return;

        for (OrderBookItemRequestWrapper item : items) {
            String name = item.getItemName();
            if (name == null || name.trim().isEmpty()) continue;

            try {
                Optional<OrderBookItemCatalogueEntity> existing =
                        catalogueRepo.findByItemNameIgnoreCase(name.trim());

                if (existing.isPresent()) {
                    // Update descriptive fields only — leave pricing untouched
                    OrderBookItemCatalogueEntity e = existing.get();
                    if (item.getSpecification() != null) e.setSpecification(item.getSpecification());
                    if (item.getDescription()   != null) e.setDescription(item.getDescription());
                    if (item.getUnit()          != null) e.setUnit(item.getUnit());
                    catalogueRepo.save(e);
                } else {
                    // New item — persist everything we know
                    OrderBookItemCatalogueEntity e = new OrderBookItemCatalogueEntity();
                    e.setItemName(name.trim());
                    e.setSpecification(item.getSpecification());
                    e.setDescription(item.getDescription());
                    e.setUnit(item.getUnit() != null ? item.getUnit() : "Nos");
                    e.setUnitPrice(
                        item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
                    e.setTaxPercent(
                        item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO);
                    e.setDiscountPercent(
                        item.getDiscountPercent() != null ? item.getDiscountPercent() : BigDecimal.ZERO);
                    catalogueRepo.save(e);
                }
            } catch (Exception ex) {
                // Non-fatal — log and continue so the order book save is never blocked
                System.err.println("[ItemCatalogue] Failed to upsert item '" + name + "': " + ex.getMessage());
            }
        }
    }
}