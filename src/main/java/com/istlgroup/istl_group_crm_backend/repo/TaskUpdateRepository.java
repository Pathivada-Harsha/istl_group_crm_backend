package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.TaskUpdateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskUpdateRepository extends JpaRepository<TaskUpdateEntity, Long> {

    List<TaskUpdateEntity> findByTaskIdOrderByUpdatedAtDesc(Long taskId);
}