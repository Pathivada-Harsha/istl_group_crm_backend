package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "project_expense_items", indexes = {
    @Index(name = "idx_pei_expense", columnList = "expense_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectExpenseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id", nullable = false)
    private ProjectExpense expense;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_mode", length = 50)
    private String paymentMode;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}