package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;

@Repository
public interface UsersRepo extends JpaRepository<UsersEntity,Long> {
	@Query(value = "SELECT * FROM users WHERE is_active = 1", nativeQuery = true)
    List<UsersEntity> findAllActiveUsers();
}
