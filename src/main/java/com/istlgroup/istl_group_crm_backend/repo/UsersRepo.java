package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;

@Repository
public interface UsersRepo extends JpaRepository<UsersEntity, Long> {

	@Query(value = "SELECT * FROM users WHERE is_active = 1", nativeQuery = true)
	List<UsersEntity> findAllActiveUsers();

	@Query("SELECT c FROM UsersEntity c WHERE c.user_id = :userid")
	UsersEntity isUserIdExist(@Param("userid") String userid);

	// Get distinct roles
	@Query("SELECT DISTINCT u.role FROM UsersEntity u ORDER BY u.role")
	List<String> findDistinctRoles();

	// Count by active status
	@Query(value = "SELECT COUNT(*) FROM users WHERE is_active = :isActive", nativeQuery = true)
	long countByIsActive(@Param("isActive") Long isActive);

	// ============================================
	// SUPERADMIN QUERIES (All Users)
	// ============================================
	
	// Get all users with pagination (no filters)
	@Query(value = """
		SELECT * FROM users
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> findAllWithPagination(
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Search by name, email, or user_id with pagination
	@Query(value = """
	    SELECT * FROM users
	    WHERE (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
	       OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
	       OR LOWER(phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
	    ORDER BY id
	    LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> searchByNameOrEmailOrUserId(
	    @Param("searchTerm") String searchTerm,
	    @Param("size") int size,
	    @Param("offset") int offset
	);

	// Count search results
	@Query(value = """
	    SELECT COUNT(*) FROM users
	    WHERE (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
	       OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
	       OR LOWER(phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
	""", nativeQuery = true)
	long countSearchResults(@Param("searchTerm") String searchTerm);

	// Find by role only with pagination
	@Query(value = """
		SELECT * FROM users
		WHERE role = :role
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> findByRole(
		@Param("role") String role,
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Count by role
	@Query(value = "SELECT COUNT(*) FROM users WHERE role = :role", nativeQuery = true)
	long countByRole(@Param("role") String role);

	// Search by name, email, or user_id AND role with pagination
	@Query(value = """
		SELECT * FROM users
		WHERE (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(user_id) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
		  AND role = :role
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> searchByNameOrEmailOrUserIdAndRole(
		@Param("searchTerm") String searchTerm,
		@Param("role") String role,
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Count search results with role filter
	@Query(value = """
		SELECT COUNT(*) FROM users
		WHERE (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(user_id) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
		  AND role = :role
	""", nativeQuery = true)
	long countSearchResultsWithRole(
		@Param("searchTerm") String searchTerm,
		@Param("role") String role
	);

	// ============================================
	// NORMAL USER QUERIES (created_by filter)
	// ============================================
	
	// Find by created_by only
	@Query(value = """
		SELECT * FROM users
		WHERE created_by = :userId
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> findByCreatedBy(
		@Param("userId") Long userId,
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Count by created_by
	@Query(value = "SELECT COUNT(*) FROM users WHERE created_by = :userId", nativeQuery = true)
	long countByCreatedBy(@Param("userId") Long userId);

	// Find by created_by + role
	@Query(value = """
		SELECT * FROM users
		WHERE created_by = :userId
		  AND role = :role
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> findByCreatedByAndRole(
		@Param("userId") Long userId,
		@Param("role") String role,
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Count by created_by + role
	@Query(value = """
		SELECT COUNT(*) FROM users
		WHERE created_by = :userId
		  AND role = :role
	""", nativeQuery = true)
	long countByCreatedByAndRole(
		@Param("userId") Long userId,
		@Param("role") String role
	);

	// Search by created_by + searchTerm
	@Query(value = """
		SELECT * FROM users
		WHERE created_by = :userId
		  AND (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(user_id) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> searchByCreatedBy(
		@Param("userId") Long userId,
		@Param("searchTerm") String searchTerm,
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Count search by created_by
	@Query(value = """
		SELECT COUNT(*) FROM users
		WHERE created_by = :userId
		  AND (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(user_id) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
	""", nativeQuery = true)
	long countSearchByCreatedBy(
		@Param("userId") Long userId,
		@Param("searchTerm") String searchTerm
	);

	// Search by created_by + searchTerm + role
	@Query(value = """
		SELECT * FROM users
		WHERE created_by = :userId
		  AND role = :role
		  AND (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(user_id) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
		ORDER BY id
		LIMIT :size OFFSET :offset
	""", nativeQuery = true)
	List<UsersEntity> searchByCreatedByAndRole(
		@Param("userId") Long userId,
		@Param("searchTerm") String searchTerm,
		@Param("role") String role,
		@Param("size") int size,
		@Param("offset") int offset
	);

	// Count search by created_by + role
	@Query(value = """
		SELECT COUNT(*) FROM users
		WHERE created_by = :userId
		  AND role = :role
		  AND (LOWER(name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
		   OR LOWER(user_id) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
	""", nativeQuery = true)
	long countSearchByCreatedByAndRole(
		@Param("userId") Long userId,
		@Param("searchTerm") String searchTerm,
		@Param("role") String role
	);

	 Optional<UsersEntity> findByEmail(String email);
	    Optional<UsersEntity> findByName(String name);
	    Optional<UsersEntity> findByPhone(String phone);
	    Optional<UsersEntity> findByRole(String role);
}