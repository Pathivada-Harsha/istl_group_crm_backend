package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;

public interface LoginRepo extends JpaRepository<LoginEntity,Long> {

	@Query("SELECT c FROM LoginEntity c WHERE (c.user_id = :username OR c.email = :username OR c.phone = :username) AND c.password = :password")
	public LoginEntity AuthenticateUser(@Param("username") String username, @Param("password") String password);
	
	@Query("SELECT u.name FROM LoginEntity u WHERE u.id = :id")
    public Optional<String> findRoleByUserId(@Param("id") Long id);

	@Query("SELECT u FROM LoginEntity u WHERE u.created_by = :userId")
	public List<LoginEntity> getAllUsers(@Param("userId") Long userId);

	@Query(value = "SELECT * FROM users ORDER BY id LIMIT :size OFFSET :offset",nativeQuery = true)
	public List<LoginEntity> findUsersWithPagination(@Param("size") int size,@Param("offset") int offset);

	@Query(value = """
		    SELECT * FROM users
		    ORDER BY id
		    LIMIT :size OFFSET :offset
		""", nativeQuery = true)
		List<LoginEntity> findAllUsersWithPagination(
		        @Param("size") int size,
		        @Param("offset") int offset
		);
	@Query(value = "SELECT COUNT(*) FROM users WHERE created_by = :userId", nativeQuery = true)
	long countUsersByCreatedBy(@Param("userId") Long userId);
	@Query(value = """
		    SELECT * FROM users
		    WHERE created_by = :userId
		    ORDER BY id
		    LIMIT :size OFFSET :offset
		""", nativeQuery = true)
		List<LoginEntity> findUsersByCreatedByWithPagination(
		        @Param("userId") Long userId,
		        @Param("size") int size,
		        @Param("offset") int offset
		);

	
	


}
