package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.BomEnforcementException;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * BomProcurementGuard — the project BOM as the boundary of what may be purchased.
 *
 * Every quotation line and purchase-order line is resolved to a live project BOM
 * line; purchase orders are then blocked if a line matches nothing, or if the
 * project's total ordered quantity for a BOM line would exceed the BOM quantity.
 * Quotations only ever warn — a quotation records what a vendor sent, whereas a
 * PO commits money.
 *
 * All matching, aggregation, self-exclusion and leniency live here so the three PO
 * write paths and the two quotation paths each need only a few lines.
 *
 * ── Things that are load-bearing ────────────────────────────────────────────
 *  • The cap key is {@code project_bom.id}, not catalogue+make. A project may
 *    legitimately hold two BOM lines for the same catalogue item under different
 *    scope phases, each with its own budget; only the line id separates them.
 *  • "Already ordered" counts live POs — deleted_at IS NULL and status <> Cancelled.
 *    DRAFT POs COUNT. Quotations never contribute: several vendors quoting the
 *    same item would multiply the figure and make every check wrong.
 *  • ONE resolver. {@link #resolveLine} resolves incoming document lines AND the
 *    persisted PO lines that make up "already ordered" ({@link #attribute}), so
 *    checking and attribution can never disagree about which BOM line a row
 *    consumes. The planned-vs-actual screen reports off that same attribution.
 *  • Self-exclusion on edit removes the WHOLE PO by id. Per-line is impossible —
 *    PO items merge positionally by line_no and the frontend sends no item ids.
 *  • Leniency (§7) keys off the PERSISTED {@code bom_match IS NULL}, which means
 *    "written before enforcement existed". It is a stored fact, not a heuristic.
 *    Leniency means "this line cannot be BLOCKED"; it never means "this line's
 *    quantity does not exist". A lenient line still consumes BOM quantity.
 *  • The guard NO-OPS when the project is unknown or its BOM is empty. Without
 *    that, switching enforcement on would block every PO on any project whose BOM
 *    has not been filled in yet.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
public class BomProcurementGuard {

    private static final Logger log = LoggerFactory.getLogger(BomProcurementGuard.class);

    /** Sentinel for "exclude no PO" — an untyped NULL bind compares unreliably in native SQL. */
    private static final long NO_EXCLUSION = -1L;

    @Autowired private ProjectBomRepo              bomRepo;
    @Autowired private DropdownProjectRepository   projectRepo;
    @Autowired private PurchaseOrderItemRepository poItemRepo;

    /** BLOCK throws on violations; WARN only reports them. */
    public enum Mode { BLOCK, WARN }

    /**
     * How a document line was tied to the BOM. Persisted verbatim into
     * {@code *.bom_match}, except LEGACY which leaves the stored NULL alone.
     *
     * <p>NAME is the FALLBACK match (item name + make + unit, §A2) — the one kind
     * reported back for confirmation, because it was inferred rather than picked.
     */
    public enum Match { ID, VARIANT, NAME, NONE, LEGACY }

    /** One incoming document line, normalised out of the raw Map payloads. */
    public record DocLine(
            int        lineNo,      // 1-based; equals the line_no the writer will assign
            String     itemName,
            String     make,        // fallback match input; blank matches anything
            String     unit,        // ditto
            BigDecimal quantity,
            Long       bomLineId,   // supplied by the picker; null for hand-typed lines
            Long       bomItemId,
            Long       variantId) {}

    /** One offending BOM line, naming EVERY document line that contributed. */
    public record Violation(
            String        code,           // NOT_IN_BOM | EXCEEDS_BOM | BOM_LINE_GONE | LEGACY_INCREASE
            List<Integer> lineNos,
            String        itemName,
            Long          bomLineId,
            BigDecimal    bomQty,
            BigDecimal    alreadyOrdered,
            BigDecimal    requested,
            BigDecimal    excess,
            String        message) {}

    /**
     * A line the guard tied to a BOM line by the fallback match rather than by a
     * catalogue reference (§A2). Reported so the user can confirm or correct it before
     * saving: a silent wrong match consumes the wrong line's budget, which is worse
     * than no match at all.
     */
    public record FallbackMatch(
            int        lineNo,
            String     itemName,
            String     make,
            String     unit,
            BigDecimal quantity,
            Long       bomLineId,
            String     bomItemName,
            String     bomMake,
            String     bomUnit,
            Long       bomScopeItemId,
            BigDecimal bomQty,
            BigDecimal alreadyOrdered,
            BigDecimal remaining,
            int        candidateCount) {}

    /** Per-line resolution plus every violation found. */
    public record CheckResult(
            Map<Integer, Long>  bomLineIdByLine,
            Map<Integer, Match> matchByLine,
            List<Violation>     violations,
            List<FallbackMatch> fallbackMatches) {

        public boolean ok() { return violations.isEmpty(); }

        /** The value to persist into bom_match for a line, or null to leave it alone. */
        public String matchToPersist(int lineNo) {
            Match m = matchByLine.get(lineNo);
            return (m == null || m == Match.LEGACY) ? null : m.name();
        }
    }

    /** One live BOM line as the picker renders it. Rates are null when gated. */
    public record BomAvailability(
            Long       bomLineId,
            Long       bomItemId,
            Long       variantId,
            Integer    seqNo,
            Long       scopeItemId,
            String     category,
            String     itemName,
            String     make,
            String     specification,
            String     unit,
            BigDecimal bomQty,
            BigDecimal alreadyOrdered,
            BigDecimal remaining,
            BigDecimal unitRate,
            BigDecimal amount) {}

    // ── Attribution: the persisted side, shared with the planned-vs-actual screen ──

    /**
     * One live purchase-order line on a project, flattened with the bits of its PO
     * header any consumer needs. Read through a projection rather than the entity so
     * a project's worth of lines costs one query and no lazy PO fetches.
     */
    public record PoLineRow(
            Long       poId,
            String     poNo,
            String     poRefId,
            Long       vendorId,
            String     vendorName,
            LocalDate  orderDate,
            String     status,
            Integer    lineNo,
            String     itemName,
            String     make,
            String     unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal taxPercent,
            Long       bomLineId,
            Long       bomItemId,
            Long       variantId,
            String     bomMatch) {

        /** Ex-GST line value — mirrors the generated {@code line_total} column. */
        public BigDecimal amount() { return nz(quantity).multiply(nz(unitPrice)); }

        /** A row written before BOM enforcement existed. */
        public boolean legacy() { return bomMatch == null; }
    }

    /** One live PO line and the BOM line it consumes ({@code bomLineId} null = unattributed). */
    public record AttributedPoLine(PoLineRow row, Long bomLineId, Match match) {}

    /** Every live PO line on a project, resolved against that project's live BOM. */
    public record Attribution(
            List<ProjectBomEntity>  bom,
            List<AttributedPoLine>  lines,
            Map<Long, BigDecimal>   orderedByBomLine) {}

    // ═════════════════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Resolve every incoming line against the project BOM and collect violations.
     * Never throws for business reasons — see {@link #enforce} for the blocking form.
     *
     * @param projectUniqueId {@code projects.project_unique_id} (what purchase_orders.project_id
     *                        holds). Null / blank / unknown ⇒ no-op: every line resolves to
     *                        NONE with zero violations. A PO without a project is
     *                        unenforceable, not illegal.
     * @param existingPoId    the PO being edited, or null for a brand-new document. Drives
     *                        both self-exclusion and leniency.
     */
    @Transactional(readOnly = true)
    public CheckResult check(String projectUniqueId, List<DocLine> lines, Long existingPoId, Mode mode) {
        Map<Integer, Long>  lineIds = new LinkedHashMap<>();
        Map<Integer, Match> matches = new LinkedHashMap<>();
        List<Violation>     violations = new ArrayList<>();
        List<FallbackMatch> fallbacks  = new ArrayList<>();

        if (lines == null) lines = List.of();
        for (DocLine l : lines) matches.put(l.lineNo(), Match.NONE);

        DropdownProjectEntity project = resolveProject(projectUniqueId);
        if (project == null) {
            log.debug("BOM guard no-op: project '{}' not resolvable", projectUniqueId);
            return new CheckResult(lineIds, matches, violations, fallbacks);
        }

        List<ProjectBomEntity> bom =
                bomRepo.findByProjectIdAndDeletedAtIsNullOrderBySeqNo(project.getId());
        if (bom.isEmpty()) {
            // Enforcing against an empty BOM would block every PO on the project.
            log.debug("BOM guard no-op: project {} has no live BOM lines", projectUniqueId);
            return new CheckResult(lineIds, matches, violations, fallbacks);
        }

        Map<Long, ProjectBomEntity> byId = new LinkedHashMap<>();
        for (ProjectBomEntity b : bom) byId.put(b.getId(), b);

        // ── Resolve each line ────────────────────────────────────────────────
        Map<Integer, Resolution> resolved = new LinkedHashMap<>();
        List<Integer> danglingLines = new ArrayList<>();
        for (DocLine l : lines) {
            Resolution r = resolveLine(l, bom, byId);
            resolved.put(l.lineNo(), r);
            if (r.hit() == null) {
                matches.put(l.lineNo(), Match.NONE);
                if (l.bomLineId() != null && !byId.containsKey(l.bomLineId())) {
                    danglingLines.add(l.lineNo());
                }
            } else {
                lineIds.put(l.lineNo(), r.hit().getId());
                matches.put(l.lineNo(), r.match());
            }
        }

        // ── §7 leniency: which lines are untouched pre-enforcement rows? ─────
        //
        // Leniency is a property of the LINE, decided independently of whether it
        // resolved. A lenient line is marked LEGACY so its NULL bom_match survives the
        // save — and so the quantity pass below knows not to blame it — but its
        // quantity is still counted. Those are two separate concerns and only the
        // first is about leniency (§A1).
        Map<Integer, PurchaseOrderItemEntity> persisted = persistedByLineNo(existingPoId);
        Set<Integer> lenient = new LinkedHashSet<>();
        for (DocLine l : lines) {
            if (isLenient(l, persisted.get(l.lineNo()))) {
                lenient.add(l.lineNo());
                matches.put(l.lineNo(), Match.LEGACY);
            }
        }

        // ── Off-BOM violations (§6.1) ────────────────────────────────────────
        for (DocLine l : lines) {
            if (resolved.get(l.lineNo()).hit() != null) continue;   // matched something
            if (lenient.contains(l.lineNo())) continue;             // matched nothing, but untouched
            boolean dangling = danglingLines.contains(l.lineNo());
            PurchaseOrderItemEntity prior = persisted.get(l.lineNo());
            boolean legacyRaised = prior != null && prior.getBomMatch() == null
                    && sameName(prior.getItemName(), l.itemName())
                    && nz(l.quantity()).compareTo(nz(prior.getQuantity())) > 0;

            if (legacyRaised) {
                violations.add(new Violation("LEGACY_INCREASE", List.of(l.lineNo()),
                        l.itemName(), null, null, null, nz(l.quantity()), null,
                        "'" + l.itemName() + "' predates BOM linking and cannot be increased. "
                      + "Delete the line and re-add it from the project BOM so it can be matched."));
            } else {
                violations.add(new Violation(dangling ? "BOM_LINE_GONE" : "NOT_IN_BOM",
                        List.of(l.lineNo()), l.itemName(), null, null, null,
                        nz(l.quantity()), null,
                        dangling
                            ? "'" + l.itemName() + "' points at a BOM line that no longer exists on this project. "
                            + "Re-pick it from the project BOM."
                            : "'" + l.itemName() + "' is not on this project's BOM. "
                            + "Add it to the BOM first, or pick the correct item from the BOM."));
            }
        }

        // ── Quantity violations (§6.2) ───────────────────────────────────────
        //
        // EVERY resolved line contributes its quantity, lenient or not: a legacy line
        // for the full BOM quantity has consumed that quantity whether or not this edit
        // may be blocked for it (§A1). What leniency buys is immunity from being
        // blamed — a BOM line is reported only when a NON-lenient line contributed to
        // the overage, so a PO whose legacy line is merely left alone still saves.
        Map<Long, BigDecimal> ordered =
                attribute(projectUniqueId, existingPoId, bom, byId).orderedByBomLine();
        Map<Long, BigDecimal>    requested    = new LinkedHashMap<>();
        Map<Long, List<Integer>> strictLines  = new LinkedHashMap<>();
        Map<Long, List<Integer>> lenientLines = new LinkedHashMap<>();

        for (DocLine l : lines) {
            Long id = lineIds.get(l.lineNo());
            if (id == null) continue;                       // unmatched — already reported above
            requested.merge(id, nz(l.quantity()), BigDecimal::add);
            (lenient.contains(l.lineNo()) ? lenientLines : strictLines)
                    .computeIfAbsent(id, k -> new ArrayList<>()).add(l.lineNo());
        }

        for (Map.Entry<Long, BigDecimal> e : requested.entrySet()) {
            ProjectBomEntity b = byId.get(e.getKey());
            if (b == null) continue;
            List<Integer> strict = strictLines.getOrDefault(e.getKey(), List.of());
            if (strict.isEmpty()) continue;                 // only untouched legacy lines — never blocked
            BigDecimal bomQty = nz(b.getQuantity());
            BigDecimal prior  = ordered.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal req    = e.getValue();
            BigDecimal excess = req.add(prior).subtract(bomQty);
            if (excess.compareTo(BigDecimal.ZERO) <= 0) continue;

            List<Integer> legacyLn = lenientLines.getOrDefault(e.getKey(), List.of());
            violations.add(new Violation("EXCEEDS_BOM", strict, b.getItemName(), b.getId(),
                    bomQty, prior, req, excess,
                    String.format(
                        "%s — BOM %s, already ordered %s (includes Draft POs), this order requests %s%s%s. Over by %s.",
                        b.getItemName(), plain(bomQty), plain(prior), plain(req),
                        strict.size() > 1 ? " across lines " + join(strict) : "",
                        legacyLn.isEmpty() ? ""
                            : " (including " + plain(sumOf(lines, legacyLn)) + " on pre-BOM line "
                              + join(legacyLn) + ", which still consumes the BOM)",
                        plain(excess))));
        }

        // ── Inferred matches, for confirmation before saving (§A2) ───────────
        for (DocLine l : lines) {
            Resolution r = resolved.get(l.lineNo());
            if (r == null || r.hit() == null || r.match() != Match.NAME) continue;
            ProjectBomEntity b = r.hit();
            BigDecimal bomQty = nz(b.getQuantity());
            BigDecimal used   = ordered.getOrDefault(b.getId(), BigDecimal.ZERO);
            BigDecimal rem    = bomQty.subtract(used);
            fallbacks.add(new FallbackMatch(
                    l.lineNo(), l.itemName(), l.make(), l.unit(), nz(l.quantity()),
                    b.getId(), b.getItemName(), b.getMake(), b.getUnit(), b.getScopeItemId(),
                    bomQty, used, rem.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rem,
                    r.candidates()));
        }

        CheckResult result = new CheckResult(lineIds, matches, violations, fallbacks);
        if (!result.ok() && mode == Mode.WARN) {
            log.info("BOM warnings for project {}: {}", projectUniqueId, result.violations());
        }
        return result;
    }

    /** {@link #check} in BLOCK mode; throws {@link BomEnforcementException} on any violation. */
    @Transactional(readOnly = true)
    public CheckResult enforce(String projectUniqueId, List<DocLine> lines, Long existingPoId) {
        CheckResult r = check(projectUniqueId, lines, existingPoId, Mode.BLOCK);
        if (!r.ok()) throw new BomEnforcementException(projectUniqueId, r.violations());
        return r;
    }

    /** {@link #check} in WARN mode — reads better at the quotation call sites. */
    @Transactional(readOnly = true)
    public CheckResult warn(String projectUniqueId, List<DocLine> lines) {
        return check(projectUniqueId, lines, null, Mode.WARN);
    }

    /**
     * Live BOM lines with their consumed and remaining quantities — what both
     * pickers render. Rates are omitted entirely when the caller's role is gated.
     */
    @Transactional(readOnly = true)
    public List<BomAvailability> availability(String projectUniqueId, Long excludePoId, boolean canSeeRates) {
        DropdownProjectEntity project = resolveProject(projectUniqueId);
        if (project == null) return List.of();

        List<ProjectBomEntity> bom =
                bomRepo.findByProjectIdAndDeletedAtIsNullOrderBySeqNo(project.getId());
        if (bom.isEmpty()) return List.of();

        Map<Long, BigDecimal> ordered =
                attributeAgainst(projectUniqueId, excludePoId, bom).orderedByBomLine();

        List<BomAvailability> out = new ArrayList<>(bom.size());
        for (ProjectBomEntity b : bom) {
            BigDecimal qty  = nz(b.getQuantity());
            BigDecimal used = ordered.getOrDefault(b.getId(), BigDecimal.ZERO);
            BigDecimal rem  = qty.subtract(used);
            if (rem.compareTo(BigDecimal.ZERO) < 0) rem = BigDecimal.ZERO;

            out.add(new BomAvailability(
                    b.getId(), b.getBomItemId(), b.getVariantId(), b.getSeqNo(), b.getScopeItemId(),
                    b.getCategory(), b.getItemName(), b.getMake(), b.getSpecification(), b.getUnit(),
                    qty, used, rem,
                    canSeeRates ? b.getUnitRate() : null,
                    canSeeRates ? b.getAmount()   : null));
        }
        return out;
    }

    /**
     * Every live PO line on a project resolved against its live BOM — the one source
     * the planned-vs-actual screen reads, so the procured quantity it shows for a BOM
     * line is by construction the same "already ordered" figure this guard enforces.
     *
     * @return null when the project is unknown. An EMPTY BOM still returns an
     *         Attribution: its PO lines are all unattributed, which is worth showing.
     */
    @Transactional(readOnly = true)
    public Attribution attributionFor(String projectUniqueId, Long excludePoId) {
        DropdownProjectEntity project = resolveProject(projectUniqueId);
        if (project == null) return null;
        List<ProjectBomEntity> bom =
                bomRepo.findByProjectIdAndDeletedAtIsNullOrderBySeqNo(project.getId());
        return attributeAgainst(projectUniqueId, excludePoId, bom);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Adapters — the write paths hand us raw Maps / entities
    // ═════════════════════════════════════════════════════════════════════════

    /** Build DocLines from the {@code List<Map<String,Object>>} all three PO paths use. */
    public static List<DocLine> fromPoItemMaps(List<Map<String, Object>> itemsData) {
        List<DocLine> out = new ArrayList<>();
        if (itemsData == null) return out;
        for (int i = 0; i < itemsData.size(); i++) {
            Map<String, Object> m = itemsData.get(i);
            out.add(new DocLine(
                    i + 1,
                    str(m.get("itemName")),
                    str(m.get("make")),
                    str(m.get("unit")),
                    dec(m.get("quantity")),
                    lng(m.get("bomLineId")),
                    lng(m.get("bomItemId")),
                    lng(m.get("variantId"))));
        }
        return out;
    }

    /** Build DocLines from quotation item entities (already in line order). */
    public static List<DocLine> fromQuotationItems(
            List<com.istlgroup.istl_group_crm_backend.entity.QuotationItemEntity> items) {
        List<DocLine> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            var q = items.get(i);
            out.add(new DocLine(
                    q.getLineNo() != null ? q.getLineNo() : i + 1,
                    q.getItemName(),
                    q.getMake(),
                    q.getUnit(),
                    q.getQuantity(),
                    q.getBomLineId(),
                    q.getBomItemId(),
                    q.getVariantId()));
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Internals
    // ═════════════════════════════════════════════════════════════════════════

    /** What {@link #resolveLine} found: the BOM line, how, and how many candidates there were. */
    private record Resolution(ProjectBomEntity hit, Match match, int candidates) {
        static final Resolution NONE = new Resolution(null, Match.NONE, 0);
    }

    private DropdownProjectEntity resolveProject(String projectUniqueId) {
        if (projectUniqueId == null || projectUniqueId.isBlank()) return null;
        return projectRepo.findByProjectUniqueId(projectUniqueId.trim()).orElse(null);
    }

    /**
     * Resolution order (§3): stored line id → catalogue+make → catalogue alone →
     * FALLBACK (item name + make + unit). Every step after the first tie-breaks on
     * lowest seqNo then lowest id, and this ONE method resolves both incoming document
     * lines and the persisted PO lines that make up "already ordered", so consumption
     * and checking cannot disagree (§A2).
     */
    private Resolution resolveLine(DocLine l, List<ProjectBomEntity> bom,
                                   Map<Long, ProjectBomEntity> byId) {
        if (l.bomLineId() != null) {
            ProjectBomEntity direct = byId.get(l.bomLineId());
            if (direct != null) return new Resolution(direct, Match.ID, 1);
            // Falls through: the line was deleted, so try to re-resolve by snapshot.
        }
        if (l.bomItemId() != null) {
            List<ProjectBomEntity> exact = bom.stream()
                    .filter(b -> l.bomItemId().equals(b.getBomItemId())
                              && java.util.Objects.equals(l.variantId(), b.getVariantId()))
                    .toList();
            if (!exact.isEmpty()) return new Resolution(pick(exact), Match.VARIANT, exact.size());

            List<ProjectBomEntity> byItem = bom.stream()
                    .filter(b -> l.bomItemId().equals(b.getBomItemId()))
                    .toList();
            if (!byItem.isEmpty()) return new Resolution(pick(byItem), Match.VARIANT, byItem.size());
        }
        // Fallback: name AND make AND unit must all agree (§A2). Name alone would let a
        // hand-typed "DC Cable" consume the FIRST DC Cable line on the BOM regardless of
        // which scope phase — and so which budget — it actually belongs to.
        List<ProjectBomEntity> named = bom.stream()
                .filter(b -> sameName(b.getItemName(), l.itemName())
                          && softEq(b.getMake(), l.make())
                          && softEq(b.getUnit(), l.unit()))
                .toList();
        if (!named.isEmpty()) return new Resolution(pick(named), Match.NAME, named.size());
        return Resolution.NONE;
    }

    /** Deterministic pick: lowest seqNo, then lowest id. */
    private ProjectBomEntity pick(List<ProjectBomEntity> candidates) {
        return candidates.stream()
                .min(Comparator
                        .comparing((ProjectBomEntity b) -> b.getSeqNo() == null ? Integer.MAX_VALUE : b.getSeqNo())
                        .thenComparing(ProjectBomEntity::getId))
                .orElse(null);
    }

    private Map<Integer, PurchaseOrderItemEntity> persistedByLineNo(Long existingPoId) {
        Map<Integer, PurchaseOrderItemEntity> out = new LinkedHashMap<>();
        if (existingPoId == null) return out;
        for (PurchaseOrderItemEntity it : poItemRepo.findByPurchaseOrderId(existingPoId)) {
            if (it.getLineNo() != null) out.put(it.getLineNo(), it);
        }
        return out;
    }

    /**
     * §7 leniency, per-line. A line is lenient only when all four hold:
     *   (a) a row already exists at this line_no;
     *   (b) that row's bom_match IS NULL — i.e. it predates enforcement;
     *   (c) the item name still matches;
     *   (d) the quantity has not been increased.
     *
     * <p>(c) is what makes this safe under the positional line_no merge: without it,
     * deleting line 2 shifts a brand-new line 5 into position 4, where it would
     * inherit position 4's legacy status and escape both checks. (d) stops an old
     * PO being used as a hole to order more than the BOM allows.
     *
     * <p>Being lenient does NOT excuse the quantity from the project's total — see
     * {@link #check}. It means only that this line is never the one blamed, and that
     * its NULL bom_match survives the save so it stays editable next time too.
     */
    private boolean isLenient(DocLine l, PurchaseOrderItemEntity prior) {
        return prior != null
            && prior.getBomMatch() == null
            && sameName(prior.getItemName(), l.itemName())
            && nz(l.quantity()).compareTo(nz(prior.getQuantity())) <= 0;
    }

    /** {@link #attribute} for a project whose BOM has already been loaded. */
    private Attribution attributeAgainst(String projectUniqueId, Long excludePoId,
                                         List<ProjectBomEntity> bom) {
        Map<Long, ProjectBomEntity> byId = new LinkedHashMap<>();
        for (ProjectBomEntity b : bom) byId.put(b.getId(), b);
        return attribute(projectUniqueId, excludePoId, bom, byId);
    }

    /**
     * Resolve every live PO line on the project against the live BOM, and total the
     * ordered quantity per BOM line.
     *
     * <p>Deliberately a row-level read folded in Java rather than a GROUP BY: a line
     * carrying no BOM link has to go through the very same {@link #resolveLine} as an
     * incoming line, and no single statement can express that — §A2 requires the two to
     * agree in every case. A project's worth of PO lines is a few hundred rows.
     */
    private Attribution attribute(String projectUniqueId, Long excludePoId,
                                  List<ProjectBomEntity> bom, Map<Long, ProjectBomEntity> byId) {
        long excl = excludePoId != null ? excludePoId : NO_EXCLUSION;
        List<AttributedPoLine> lines = new ArrayList<>();
        Map<Long, BigDecimal>  ordered = new LinkedHashMap<>();

        for (Object[] r : poItemRepo.findLivePoLinesForProject(projectUniqueId, excl)) {
            PoLineRow row = toPoLineRow(r);
            if (row == null) continue;
            Resolution res = bom.isEmpty()
                    ? Resolution.NONE
                    : resolveLine(new DocLine(row.lineNo() != null ? row.lineNo() : 0,
                                              row.itemName(), row.make(), row.unit(), row.quantity(),
                                              row.bomLineId(), row.bomItemId(), row.variantId()),
                                  bom, byId);
            Long hitId = res.hit() != null ? res.hit().getId() : null;
            lines.add(new AttributedPoLine(row, hitId, res.match()));
            if (hitId != null) {
                ordered.merge(hitId, nz(row.quantity()), BigDecimal::add);
            } else {
                log.debug("Unattributable PO quantity '{}' on project {}", row.itemName(), projectUniqueId);
            }
        }
        return new Attribution(bom, lines, ordered);
    }

    /** Column order must match {@code PurchaseOrderItemRepository#findLivePoLinesForProject}. */
    private static PoLineRow toPoLineRow(Object[] r) {
        if (r == null || r.length < 18 || r[0] == null) return null;
        return new PoLineRow(
                lng(r[0]), str(r[1]), str(r[2]), lng(r[3]), str(r[4]),
                date(r[5]), str(r[6]),
                r[7] == null ? null : ((Number) r[7]).intValue(),
                str(r[8]), str(r[9]), str(r[10]),
                dec(r[11]), dec(r[12]), dec(r[13]),
                lng(r[14]), lng(r[15]), lng(r[16]), str(r[17]));
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static boolean sameName(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Fallback-match comparison for make and unit: equal ignoring case and surrounding
     * whitespace, with a blank on EITHER side matching anything. Older BOM lines and
     * legacy PO rows often recorded neither, and refusing to match those would strand
     * exactly the rows the fallback exists for (§A2).
     */
    private static boolean softEq(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) return true;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static BigDecimal sumOf(List<DocLine> lines, List<Integer> lineNos) {
        BigDecimal t = BigDecimal.ZERO;
        for (DocLine l : lines) if (lineNos.contains(l.lineNo())) t = t.add(nz(l.quantity()));
        return t;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static String plain(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String join(List<Integer> ln) {
        return ln.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String str(Object o) { return o != null ? o.toString() : null; }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof LocalDate d) return d;
        try { return LocalDate.parse(o.toString().substring(0, 10)); } catch (Exception e) { return null; }
    }

    private static Long lng(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(6, RoundingMode.HALF_UP);
        String s = o.toString().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
