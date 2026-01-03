package com.istlgroup.istl_group_crm_backend.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.PagePermissionsEntity;
@Repository
public interface PagePermissionsRepo extends JpaRepository<PagePermissionsEntity,Long> {

	@Query("SELECT c FROM PagePermissionsEntity c WHERE c.user_id = :user_id")
	Optional<PagePermissionsEntity> findByUserId(Long user_id);
	



}
