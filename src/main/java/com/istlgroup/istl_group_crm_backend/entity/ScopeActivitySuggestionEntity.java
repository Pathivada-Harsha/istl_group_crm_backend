package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A user-contributed Technical-Scope activity/item name. Built-in suggestions live
 * in the frontend; this table only holds names users typed that weren't already
 * built in, so the dropdown grows and is shared across users and devices.
 */
@Entity
@Table(name = "scope_activity_suggestion")
@Data
public class ScopeActivitySuggestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255, unique = true)
    private String name;

    @Column(name = "use_count", nullable = false)
    private Integer useCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}