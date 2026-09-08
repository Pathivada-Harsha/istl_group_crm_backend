package com.istlgroup.istl_group_crm_backend.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookPhaseEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectPhaseEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectProgressPeriodEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookProgressPeriodEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookPhaseRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookProgressPeriodRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectPhaseRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectProgressPeriodRepo;
import com.istlgroup.istl_group_crm_backend.service.scope.ScopeSubItems;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time migration that gives every existing scope sub-item a stable id and re-points
 * the progress and budget history that used to be keyed by its NAME.
 *
 * <p><b>Why this exists.</b> Before nesting, a sub-item's identity was its name string:
 * {@code project_progress_periods.sub_item_key} held the name, and the planned-budget
 * merge matched on it with an exact equals. That is only safe while the breakdown is one
 * flat level. A tree repeats names across branches by design — a real schedule has
 * "Payment", "Receiving" and "Installation" under many parents — so the key had to become
 * an id. Every row already in the database points at a name, and this is what moves them.
 *
 * <p><b>What it does</b>, per project (and the order-book mirror):
 * <ol>
 *   <li>Walks each phase's {@code sub_items} JSON and mints a UUID for every node that
 *       lacks one, remembering the name it had.</li>
 *   <li>Rewrites that phase's progress rows so {@code sub_item_key} holds the new id
 *       instead of the name.</li>
 *   <li>Does the same for {@code project_bom.scope_sub_item_key}.</li>
 * </ol>
 *
 * <p><b>Matching is per phase, never global.</b> A name is resolved only against the nodes
 * of the phase the progress row already belongs to, which is exactly the scope in which
 * the old name key was unique-ish. Matching across phases would be the very bug this
 * change exists to prevent.
 *
 * <p><b>Idempotent.</b> A node that already has an id is left alone, and a progress row
 * whose key already matches a known id is left alone. Re-running changes nothing, so it is
 * safe to leave the flag on through a restart.
 *
 * <p><b>Nothing is deleted.</b> A progress row whose name matches no node in its phase is
 * left exactly as it is and counted as UNMATCHED in the summary. Such a row was already
 * orphaned before this migration (it is the fingerprint of the old rename bug), and
 * deleting it would destroy the only remaining record of that work. It is reported so it
 * can be looked at, not silently cleaned up.
 *
 * <p>ENABLE: set {@code scope.node-id.migration.enabled=true}. Default FALSE — you opt in
 * explicitly, and the log prints a per-phase before/after count to check against the
 * verification SQL before the flag goes back off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeNodeIdMigrationRunner implements ApplicationRunner {

    private final ProjectPhaseRepo projectPhaseRepo;
    private final ProjectProgressPeriodRepo projectProgressRepo;
    private final ProjectBomRepo projectBomRepo;
    private final OrderBookPhaseRepo orderBookPhaseRepo;
    private final OrderBookProgressPeriodRepo orderBookProgressRepo;
    private final LeadScopeItemRepo leadScopeItemRepo;
    private final LeadScopeTemplateItemRepo leadScopeTemplateItemRepo;
    private final ObjectMapper objectMapper;
    /**
     * Used explicitly rather than annotating the steps with {@code @Transactional}: they
     * are called from {@link #run} on this same bean, and a self-invocation never passes
     * through the Spring proxy, so the annotation would have been silently inert. Each step
     * is its own transaction, so one failing step cannot half-apply another — and because
     * the migration is idempotent, a re-run finishes whatever did not complete.
     */
    private final TransactionTemplate txTemplate;

    @org.springframework.beans.factory.annotation.Value("${scope.node-id.migration.enabled:false}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[ScopeNodeIds] Skipped — set scope.node-id.migration.enabled=true to run.");
            return;
        }
        log.info("[ScopeNodeIds] ===== Starting scope node-id migration =====");
        try {
            txTemplate.executeWithoutResult(tx -> migrateProjects());
            txTemplate.executeWithoutResult(tx -> migrateOrderBook());
            txTemplate.executeWithoutResult(tx -> migrateDefinitionOnly());
            log.info("[ScopeNodeIds] ===== Migration complete =====");
        } catch (Exception e) {
            // Logged rather than rethrown: a failure here must not stop the application
            // booting, and the migration is idempotent so it can simply be run again.
            log.error("[ScopeNodeIds] Migration FAILED — nothing partially applied is lost, "
                    + "fix the cause and run again.", e);
        }
    }

    // ── Projects ─────────────────────────────────────────────────────────────────

    private void migrateProjects() {
        List<ProjectPhaseEntity> phases = projectPhaseRepo.findAll();
        int touchedPhases = 0, mintedIds = 0, reKeyed = 0, unmatched = 0, bomReKeyed = 0;

        for (ProjectPhaseEntity ph : phases) {
            Map<String, String> idByName = new LinkedHashMap<>();
            String migrated = assignIds(ph.getSubItems(), idByName);
            if (migrated == null) continue;

            ph.setSubItems(migrated);
            projectPhaseRepo.save(ph);
            touchedPhases++;
            mintedIds += idByName.size();

            List<ProjectProgressPeriodEntity> rows = projectProgressRepo.findByPhaseId(ph.getId());
            for (ProjectProgressPeriodEntity r : rows) {
                String key = r.getSubItemKey();
                if (key == null || key.isBlank()) continue;          // the phase itself — no change
                if (isKnownId(key, ph.getSubItems())) continue;      // already an id
                String id = idByName.get(ScopeSubItems.nameKey(key));
                if (id == null) {
                    unmatched++;
                    log.warn("[ScopeNodeIds] project phase {} (\"{}\"): progress key \"{}\" matches no "
                            + "sub-item — left untouched, this row was already orphaned.",
                            ph.getId(), ph.getPhaseName(), key);
                    continue;
                }
                r.setSubItemKey(id);
                projectProgressRepo.save(r);
                reKeyed++;
            }

            bomReKeyed += reKeyBom(ph.getProjectId(), ph.getId(), idByName);
        }
        log.info("[ScopeNodeIds] projects: {} phases given ids ({} nodes), {} progress rows re-keyed, "
                + "{} BOM links re-keyed, {} progress rows UNMATCHED (left as-is).",
                touchedPhases, mintedIds, reKeyed, bomReKeyed, unmatched);
    }

    /**
     * Re-point {@code project_bom.scope_sub_item_key} for one phase.
     *
     * <p>This column is written but never read back by the UI today, so it is migrated for
     * consistency rather than because a screen depends on it — leaving half the database
     * keyed by name would be a trap for whoever wires it up later.
     */
    private int reKeyBom(Long projectId, Long phaseId, Map<String, String> idByName) {
        int n = 0;
        for (var bom : projectBomRepo
                .findByProjectIdAndScopeItemIdAndDeletedAtIsNullOrderBySeqNo(projectId, phaseId)) {
            String key = bom.getScopeSubItemKey();
            if (key == null || key.isBlank()) continue;
            String id = idByName.get(ScopeSubItems.nameKey(key));
            if (id == null || id.equals(key)) continue;
            bom.setScopeSubItemKey(id);
            projectBomRepo.save(bom);
            n++;
        }
        return n;
    }

    // ── Order book (the same model, its own tables) ───────────────────────────────

    private void migrateOrderBook() {
        List<OrderBookPhaseEntity> phases = orderBookPhaseRepo.findAll();
        int touched = 0, reKeyed = 0, unmatched = 0;

        for (OrderBookPhaseEntity ph : phases) {
            Map<String, String> idByName = new LinkedHashMap<>();
            String migrated = assignIds(ph.getSubItems(), idByName);
            if (migrated == null) continue;

            ph.setSubItems(migrated);
            orderBookPhaseRepo.save(ph);
            touched++;

            for (OrderBookProgressPeriodEntity r : orderBookProgressRepo.findByPhaseId(ph.getId())) {
                String key = r.getSubItemKey();
                if (key == null || key.isBlank()) continue;
                if (isKnownId(key, ph.getSubItems())) continue;
                String id = idByName.get(ScopeSubItems.nameKey(key));
                if (id == null) { unmatched++; continue; }
                r.setSubItemKey(id);
                orderBookProgressRepo.save(r);
                reKeyed++;
            }
        }
        log.info("[ScopeNodeIds] order book: {} phases given ids, {} progress rows re-keyed, "
                + "{} UNMATCHED (left as-is).", touched, reKeyed, unmatched);
    }

    // ── Templates and leads (definition only — no history to re-point) ────────────

    /**
     * Templates and lead scope items carry no progress or budget, so they only need ids
     * minting. Doing it here rather than lazily on next save means a lead opened after the
     * migration already matches its project by id instead of falling back to a name.
     */
    private void migrateDefinitionOnly() {
        int t = 0, l = 0;
        for (LeadScopeTemplateItemEntity it : leadScopeTemplateItemRepo.findAll()) {
            String migrated = assignIds(it.getSubItems(), new LinkedHashMap<>());
            if (migrated == null) continue;
            it.setSubItems(migrated);
            leadScopeTemplateItemRepo.save(it);
            t++;
        }
        for (LeadScopeItemEntity it : leadScopeItemRepo.findAll()) {
            String migrated = assignIds(it.getSubItems(), new LinkedHashMap<>());
            if (migrated == null) continue;
            it.setSubItems(migrated);
            leadScopeItemRepo.save(it);
            l++;
        }
        log.info("[ScopeNodeIds] definitions: {} template lines, {} lead scope items given ids.", t, l);
    }

    // ── Shared walk ──────────────────────────────────────────────────────────────

    /**
     * Mint ids through one {@code sub_items} JSON tree.
     *
     * @param idByName filled with {@code nameKey(name) → new id} for every node that had to
     *                 be given one, so the caller can re-point history that names it. First
     *                 wins on a duplicate name, which is what the old name-keyed lookups
     *                 resolved to, so history lands where it used to read from.
     * @return the rewritten JSON, or null when there was nothing to do (no breakdown,
     *         unparseable, or every node already has an id — the idempotent case)
     */
    private String assignIds(String json, Map<String, String> idByName) {
        if (json == null || json.isBlank()) return null;
        List<Map<String, Object>> nodes;
        try {
            nodes = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[ScopeNodeIds] unparseable sub_items, skipped: {}", e.getMessage());
            return null;
        }
        if (nodes == null || nodes.isEmpty()) return null;

        boolean[] changed = { false };
        walk(nodes, idByName, changed);
        if (!changed[0]) return null;
        try {
            return objectMapper.writeValueAsString(nodes);
        } catch (Exception e) {
            log.warn("[ScopeNodeIds] could not re-serialise sub_items, skipped: {}", e.getMessage());
            return null;
        }
    }

    private void walk(List<Map<String, Object>> nodes, Map<String, String> idByName, boolean[] changed) {
        for (Map<String, Object> n : nodes) {
            if (n == null) continue;
            String id = ScopeSubItems.idOf(n);
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
                n.put("id", id);
                changed[0] = true;
            }
            String name = n.get("name") == null ? null : String.valueOf(n.get("name"));
            idByName.putIfAbsent(ScopeSubItems.nameKey(name), id);
            walk(ScopeSubItems.childrenOf(n), idByName, changed);
        }
    }

    /** True when this key is already an id somewhere in the tree — the re-run case. */
    private boolean isKnownId(String key, String json) {
        if (json == null || json.isBlank()) return false;
        try {
            List<Map<String, Object>> nodes =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Boolean> ids = new HashMap<>();
            collectIds(nodes, ids);
            return ids.containsKey(key);
        } catch (Exception e) {
            return false;
        }
    }

    private void collectIds(List<Map<String, Object>> nodes, Map<String, Boolean> out) {
        if (nodes == null) return;
        for (Map<String, Object> n : nodes) {
            if (n == null) continue;
            String id = ScopeSubItems.idOf(n);
            if (!id.isEmpty()) out.put(id, Boolean.TRUE);
            collectIds(ScopeSubItems.childrenOf(n), out);
        }
    }
}
