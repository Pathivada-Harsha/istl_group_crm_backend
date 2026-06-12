package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.TelecallerAssignmentTracker;
import com.istlgroup.istl_group_crm_backend.repo.TelecallerAssignmentTrackerRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Round-robin assignment service for Telecaller employees.
 *
 * Algorithm:
 *  1. Load all active TELECALLER users for the given group/subgroup scope.
 *  2. Sort them deterministically by userId (ascending) so the order is
 *     stable across restarts.
 *  3. Find the index of the last-assigned telecaller from the DB tracker.
 *  4. Return the next telecaller (index + 1, wrapping around).
 *  5. Persist the new last-assigned id with a pessimistic lock to prevent
 *     race conditions in concurrent requests.
 */
@Service
@Slf4j
public class RoundRobinAssignmentService {

    private static final String GLOBAL = "GLOBAL";

    @Autowired
    private TelecallerAssignmentTrackerRepo trackerRepo;

    @Autowired
    private UsersRepo usersRepo;

    /**
     * Returns the userId of the next Telecaller to assign, or null if
     * no active telecallers are available.
     *
     * @param groupName    lead's group (may be null)
     * @param subGroupName lead's sub-group (may be null)
     */
    @Transactional
    public Long getNextTelecaller(String groupName, String subGroupName) {
        String scope    = blankToGlobal(groupName);
        String subScope = blankToGlobal(subGroupName);

        // 1. Fetch active telecallers for this scope
        List<Long> telecallerIds = getActiveTelecallerIds(scope, subScope);

        if (telecallerIds.isEmpty()) {
            log.warn("No active telecallers found for scope={}/{}", scope, subScope);
            return null;
        }

        // 2. Retrieve or create the tracker row (with pessimistic lock)
        TelecallerAssignmentTracker tracker =
            trackerRepo.findByGroupAndSubGroupForUpdate(scope, subScope)
                .orElseGet(() -> {
                    TelecallerAssignmentTracker t = new TelecallerAssignmentTracker();
                    t.setGroupName(scope);
                    t.setSubGroupName(subScope);
                    t.setLastAssignedUserId(null);
                    return t;
                });

        // 3. Find next index
        Long lastId = tracker.getLastAssignedUserId();
        int  nextIdx;

        if (lastId == null || !telecallerIds.contains(lastId)) {
            // First assignment or last person left the pool → start from 0
            nextIdx = 0;
        } else {
            int lastIdx = telecallerIds.indexOf(lastId);
            nextIdx = (lastIdx + 1) % telecallerIds.size();
        }

        Long nextTelecallerId = telecallerIds.get(nextIdx);

        // 4. Persist updated tracker
        tracker.setLastAssignedUserId(nextTelecallerId);
        trackerRepo.save(tracker);

        log.info("Round-robin assigned telecaller {} for scope={}/{}",
                 nextTelecallerId, scope, subScope);
        return nextTelecallerId;
    }

    /**
     * Fetches sorted user IDs for role=TELECALLER filtered by group/subgroup.
     * Falls back to all TELECALLER users when scope is GLOBAL.
     */
    private List<Long> getActiveTelecallerIds(String scope, String subScope) {
        List<Long> ids;

        if (GLOBAL.equals(scope)) {
            ids = usersRepo.findActiveTelecallerIds();
        } else if (GLOBAL.equals(subScope)) {
            ids = usersRepo.findActiveTelecallerIdsByGroup(scope);
        } else {
            ids = usersRepo.findActiveTelecallerIdsByGroupAndSubGroup(scope, subScope);
            // fallback to group-level if no telecallers in subgroup
            if (ids.isEmpty()) {
                ids = usersRepo.findActiveTelecallerIdsByGroup(scope);
            }
            // fallback to global if still empty
            if (ids.isEmpty()) {
                ids = usersRepo.findActiveTelecallerIds();
            }
        }

        // Sort by id for deterministic ordering
        ids.sort(Comparator.naturalOrder());
        return ids;
    }
    @Transactional
    public Long getNextBD(String groupName, String subGroupName) {
        List<Long> bdIds = usersRepo.findActiveBDIds();
        if (bdIds == null || bdIds.isEmpty()) {
            log.warn("No active BD Executive users found for round-robin assignment.");
            return null;
        }

        // Uses correct repo method name: findByGroupAndSubGroupForUpdate
        TelecallerAssignmentTracker tracker = trackerRepo
            .findByGroupAndSubGroupForUpdate("BD_GLOBAL", "BD_GLOBAL")
            .orElseGet(() -> {
                TelecallerAssignmentTracker t = new TelecallerAssignmentTracker();
                t.setGroupName("BD_GLOBAL");
                t.setSubGroupName("BD_GLOBAL");
                return t;
            });

        Long lastId = tracker.getLastAssignedUserId();
        Long nextId = bdIds.get(0);   // default: first in list

        if (lastId != null) {
            int idx = bdIds.indexOf(lastId);
            // Move to next — wraps back to 0 if at end of list
            nextId = bdIds.get((idx + 1) % bdIds.size());
        }

        tracker.setLastAssignedUserId(nextId);
        tracker.setUpdatedAt(java.time.LocalDateTime.now());
        trackerRepo.save(tracker);

        log.info("BD round-robin: assigned user ID {} (from {} active BD users)", nextId, bdIds.size());
        return nextId;
    }


    private String blankToGlobal(String s) {
        return (s == null || s.trim().isEmpty()) ? GLOBAL : s.trim();
    }
}