package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.TaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    // ── Basic (no pagination) ─────────────────────────────────────────────
    List<TaskEntity> findByAssignedToOrderByCreatedAtDesc(Long assignedTo);
    List<TaskEntity> findAllByOrderByCreatedAtDesc();

    // ── Paginated + search (Table / Board view) ───────────────────────────
    @Query("SELECT t FROM TaskEntity t " +
           "WHERE t.assignedTo = :userId " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.projectName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.relatedTo) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "  AND (:status IS NULL OR :status = '' OR t.status = :status) " +
           "  AND (:priority IS NULL OR :priority = '' OR t.priority = :priority) " +
           "  AND (:category IS NULL OR :category = '' OR t.category = :category) " +
           "  AND (:dateFrom IS NULL OR t.dueDate >= :dateFrom) " +
           "  AND (:dateTo   IS NULL OR t.dueDate <= :dateTo) " +
           "ORDER BY t.createdAt DESC")
    Page<TaskEntity> searchByUser(
            @Param("userId")   Long userId,
            @Param("search")   String search,
            @Param("status")   String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo")   LocalDate dateTo,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM TaskEntity t " +
           "WHERE t.assignedTo = :userId " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.projectName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.relatedTo) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "  AND (:status IS NULL OR :status = '' OR t.status = :status) " +
           "  AND (:priority IS NULL OR :priority = '' OR t.priority = :priority) " +
           "  AND (:category IS NULL OR :category = '' OR t.category = :category) " +
           "  AND (:dateFrom IS NULL OR t.dueDate >= :dateFrom) " +
           "  AND (:dateTo   IS NULL OR t.dueDate <= :dateTo)")
    long countSearchByUser(
            @Param("userId")   Long userId,
            @Param("search")   String search,
            @Param("status")   String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo")   LocalDate dateTo);

    // SA: all users with same filters
    @Query("SELECT t FROM TaskEntity t " +
           "WHERE (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.projectName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.relatedTo) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "  AND (:status IS NULL OR :status = '' OR t.status = :status) " +
           "  AND (:priority IS NULL OR :priority = '' OR t.priority = :priority) " +
           "  AND (:category IS NULL OR :category = '' OR t.category = :category) " +
           "  AND (:dateFrom IS NULL OR t.dueDate >= :dateFrom) " +
           "  AND (:dateTo   IS NULL OR t.dueDate <= :dateTo) " +
           "ORDER BY t.createdAt DESC")
    Page<TaskEntity> searchAll(
            @Param("search")   String search,
            @Param("status")   String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo")   LocalDate dateTo,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM TaskEntity t " +
           "WHERE (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.projectName) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.relatedTo) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "  AND (:status IS NULL OR :status = '' OR t.status = :status) " +
           "  AND (:priority IS NULL OR :priority = '' OR t.priority = :priority) " +
           "  AND (:category IS NULL OR :category = '' OR t.category = :category) " +
           "  AND (:dateFrom IS NULL OR t.dueDate >= :dateFrom) " +
           "  AND (:dateTo   IS NULL OR t.dueDate <= :dateTo)")
    long countSearchAll(
            @Param("search")   String search,
            @Param("status")   String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo")   LocalDate dateTo);

    // ── Manager Team View: tasks for users where manager_id = managerId ──
    /**
     * Returns tasks assigned to any direct report of the given manager.
     * If a specific reportUserId is provided, filters to that one report.
     * Date filter is on task.createdAt (not update timestamp) so managers
     * see all tasks of their reports regardless of whether updates exist.
     */
    @Query("SELECT t FROM TaskEntity t " +
           "WHERE t.assignedTo IN " +
           "      (SELECT u.id FROM UsersEntity u WHERE u.managerId = :managerId AND u.is_active = 1) " +
           "  AND (:reportUserId IS NULL OR t.assignedTo = :reportUserId) " +
           "  AND (:fromDt IS NULL OR t.createdAt >= :fromDt) " +
           "  AND (:toDt   IS NULL OR t.createdAt < :toDt) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "ORDER BY t.createdAt DESC")
    Page<TaskEntity> searchManagerTeam(
            @Param("managerId")     Long managerId,
            @Param("reportUserId") Long reportUserId,
            @Param("fromDt")       LocalDateTime fromDt,
            @Param("toDt")         LocalDateTime toDt,
            @Param("search")       String search,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM TaskEntity t " +
           "WHERE t.assignedTo IN " +
           "      (SELECT u.id FROM UsersEntity u WHERE u.managerId = :managerId AND u.is_active = 1) " +
           "  AND (:reportUserId IS NULL OR t.assignedTo = :reportUserId) " +
           "  AND (:fromDt IS NULL OR t.createdAt >= :fromDt) " +
           "  AND (:toDt   IS NULL OR t.createdAt < :toDt) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')))")
    long countManagerTeam(
            @Param("managerId")     Long managerId,
            @Param("reportUserId") Long reportUserId,
            @Param("fromDt")       LocalDateTime fromDt,
            @Param("toDt")         LocalDateTime toDt,
            @Param("search")       String search);

    // ── Team View: show ALL tasks for user, filter by createdAt ─────────────
    // FIX: was JOIN (INNER JOIN on updates) — tasks without updates were hidden.
    // Now uses createdAt on the task itself so ALL tasks for a user show up.
    @Query("SELECT t FROM TaskEntity t " +
           "WHERE t.assignedTo = :userId " +
           "  AND (:fromDt IS NULL OR t.createdAt >= :fromDt) " +
           "  AND (:toDt   IS NULL OR t.createdAt < :toDt) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "ORDER BY t.createdAt DESC")
    Page<TaskEntity> searchTeamByUser(
            @Param("userId") Long userId,
            @Param("fromDt") LocalDateTime fromDt,
            @Param("toDt")   LocalDateTime toDt,
            @Param("search") String search,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM TaskEntity t " +
           "WHERE t.assignedTo = :userId " +
           "  AND (:fromDt IS NULL OR t.createdAt >= :fromDt) " +
           "  AND (:toDt   IS NULL OR t.createdAt < :toDt) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')))")
    long countTeamByUser(
            @Param("userId") Long userId,
            @Param("fromDt") LocalDateTime fromDt,
            @Param("toDt")   LocalDateTime toDt,
            @Param("search") String search);

    @Query("SELECT t FROM TaskEntity t " +
           "WHERE (:fromDt IS NULL OR t.createdAt >= :fromDt) " +
           "  AND (:toDt   IS NULL OR t.createdAt < :toDt) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%'))) " +
           "ORDER BY t.createdAt DESC")
    Page<TaskEntity> searchTeamAll(
            @Param("fromDt") LocalDateTime fromDt,
            @Param("toDt")   LocalDateTime toDt,
            @Param("search") String search,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM TaskEntity t " +
           "WHERE (:fromDt IS NULL OR t.createdAt >= :fromDt) " +
           "  AND (:toDt   IS NULL OR t.createdAt < :toDt) " +
           "  AND (:search IS NULL OR :search = '' OR " +
           "       LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.taskCode) LIKE LOWER(CONCAT('%',:search,'%')) OR " +
           "       LOWER(t.assignedToName) LIKE LOWER(CONCAT('%',:search,'%')))")
    long countTeamAll(
            @Param("fromDt") LocalDateTime fromDt,
            @Param("toDt")   LocalDateTime toDt,
            @Param("search") String search);

    // ── Lookups & counts ──────────────────────────────────────────────────
    Optional<TaskEntity> findByTaskCode(String taskCode);
    long countByAssignedToAndDueDate(Long assignedTo, LocalDate dueDate);

    @Query("SELECT COUNT(t) FROM TaskEntity t " +
           "WHERE t.assignedTo = :userId " +
           "  AND t.status NOT IN ('Completed','Cancelled') " +
           "  AND t.dueDate < :today")
    long countOverdueByUser(@Param("userId") Long userId, @Param("today") LocalDate today);

    @Query("SELECT COUNT(t) FROM TaskEntity t")
    long countAllTasks();

    /**
     * Returns the highest numeric suffix from existing task codes (e.g. TSK-0007 → 7).
     * Returns 0 if no tasks exist. Used to generate the next unique task code safely
     * regardless of deletions in the middle.
     */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(task_code, 5) AS UNSIGNED)), 0) FROM tasks", nativeQuery = true)
    long findMaxTaskNumber();

    // ── Notification scheduler (additive) ─────────────────────────────
    List<TaskEntity> findByDueDate(LocalDate dueDate);        // due today
    List<TaskEntity> findByDueDateBefore(LocalDate date);     // overdue
}