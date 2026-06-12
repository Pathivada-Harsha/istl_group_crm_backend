package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.TelecallerAssignmentTracker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface TelecallerAssignmentTrackerRepo
        extends JpaRepository<TelecallerAssignmentTracker, Long> {

    /**
     * Pessimistic write lock so concurrent lead-creation calls
     * don't read the same "last assigned" value simultaneously.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TelecallerAssignmentTracker t " +
           "WHERE t.groupName = :groupName AND t.subGroupName = :subGroupName")
    Optional<TelecallerAssignmentTracker> findByGroupAndSubGroupForUpdate(
            @Param("groupName")    String groupName,
            @Param("subGroupName") String subGroupName);
}