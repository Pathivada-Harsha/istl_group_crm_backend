package com.istlgroup.istl_group_crm_backend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.MenuItemsEntity;



@Repository
public interface MenuItemsRepo extends JpaRepository<MenuItemsEntity, Integer> {

    // Gets the role of the creator user — used when adding a new menu item
    @Query(value = "SELECT role FROM users WHERE id = :userId LIMIT 1", nativeQuery = true)
    String getCreatorRoleName(@Param("userId") Long userId);
}