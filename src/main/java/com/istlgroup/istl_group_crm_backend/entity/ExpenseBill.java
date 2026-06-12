package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bill / receipt for a project expense, stored as raw bytes in the database
 * (mirrors the user_profile_images BLOB pattern). One bill per expense.
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "expense_bills")
public class ExpenseBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expense_id", nullable = false, unique = true)
    private Long expenseId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    // Raw binary bytes — MEDIUMBLOB supports up to 16 MB
    @Lob
    @Column(name = "file_data", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] fileData;

    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private LocalDateTime uploadedAt;
}