package com.istlgroup.istl_group_crm_backend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.MenuPermissionsEntity;

@Repository
public interface MenuPermissionsRepo extends JpaRepository<MenuPermissionsEntity,Long>{

	MenuPermissionsEntity findByUsersId(Long usersId);

}
