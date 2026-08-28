package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectPhaseEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectPhaseRepo;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.AttributedPoLine;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.Attribution;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.PoLineRow;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * ProjectBomActualsService — Planned vs Procured for one project's BOM.
 *
 * PLANNED is the project BOM as entered. PROCURED is purchase-order value ONLY:
 * not project expenses, not invoices, not payments. A PO raised is a commitment,
 * which is why the column is called Procured and never "Spent" — and why this
 * screen's total will not equal the project financials total.
 *
 * ── Why every figure here comes from BomProcurementGuard.attributionFor ─────
 * The enforcement guard blocks a purchase order using its own idea of "already
 * ordered" per BOM line. If this screen computed the same number a second way, the
 * two would eventually disagree, and a figure that contradicts the block message a
 * buyer saw an hour ago destroys confidence in both. So there is exactly one
 * attribution: the guard resolves every live PO line (linked, legacy or hand-typed)
 * against the live BOM, and this service only groups and totals what it returns.
 * That makes "procured qty shown == already-ordered qty enforced" true by
 * construction rather than by agreement.
 *
 * ── Definitions applied identically everywhere, including rollups ────────────
 *  • Live PO   — every status except Cancelled, soft-deleted rows excluded. DRAFT
 *                POs ARE included (the guard counts them, so this must too).
 *  • Line-level comparison is EX-GST on both sides; GST appears only in the
 *    summary block at the foot.
 *  • Planned GST comes from project_bom.gst_percent — copied from the catalogue
 *    when the line was created. A line with no rate recorded goes into the
 *    "no rate" row, never a silent 0%, which would understate planned GST.
 *  • Procured GST comes from purchase_order_items.tax_percent as billed.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
public class ProjectBomActualsService {

    /** Rounding for every money figure this service emits. */
    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE   = 3;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Autowired private BomProcurementGuard      guard;
    @Autowired private DropdownProjectRepository projectRepo;
    @Autowired private ProjectPhaseRepo          phaseRepo;

    /**
     * Everything the three BOM sub-tabs render, in one read.
     *
     * @param canSeeRates when false EVERY money figure is omitted — including
     *                    variance, which discloses the planned rate by arithmetic
     *                    even when the planned column itself is hidden. Quantities
     *                    and GST RATES stay: neither is a price.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> plannedVsActual(String projectUniqueId, boolean canSeeRates)
            throws CustomException {
        DropdownProjectEntity project = projectRepo.findByProjectUniqueId(projectUniqueId)
                .orElseThrow(() -> new CustomException("Project not found: " + projectUniqueId));

        Attribution att = guard.attributionFor(projectUniqueId, null);
        List<ProjectBomEntity> bom = att != null ? att.bom() : List.of();
        List<AttributedPoLine> poLines = att != null ? att.lines() : List.of();

        // BOM lines by id, and the PO lines feeding each one.
        Map<Long, ProjectBomEntity>       bomById  = new LinkedHashMap<>();
        Map<Long, List<AttributedPoLine>> feeding  = new LinkedHashMap<>();
        for (ProjectBomEntity b : bom) {
            bomById.put(b.getId(), b);
            feeding.put(b.getId(), new ArrayList<>());
        }
        List<AttributedPoLine> unattributed = new ArrayList<>();
        for (AttributedPoLine l : poLines) {
            if (l.bomLineId() != null && feeding.containsKey(l.bomLineId())) {
                feeding.get(l.bomLineId()).add(l);
            } else {
                unattributed.add(l);
            }
        }

        // Scope names. A BOM line whose scope_item_id points at nothing live is
        // Unassigned — without that bucket the scope totals would not sum to the
        // project total and the missing lines would simply be invisible.
        Map<Long, String> scopeNames = new LinkedHashMap<>();
        for (ProjectPhaseEntity p : phaseRepo.findByProjectIdOrderBySeqNo(project.getId())) {
            scopeNames.put(p.getId(), p.getPhaseName() != null && !p.getPhaseName().isBlank()
                    ? p.getPhaseName() : "(unnamed activity)");
        }

        // ── BOM line rows, grouped by scope in scope order ───────────────────
        Map<Long, List<Map<String, Object>>> linesByScope = new LinkedHashMap<>();
        for (Long scopeId : scopeNames.keySet()) linesByScope.put(scopeId, new ArrayList<>());
        List<Map<String, Object>> unassignedLines = new ArrayList<>();

        for (ProjectBomEntity b : bom) {
            Map<String, Object> row = bomLineRow(b, feeding.get(b.getId()), canSeeRates);
            Long scopeId = b.getScopeItemId();
            if (scopeId != null && linesByScope.containsKey(scopeId)) linesByScope.get(scopeId).add(row);
            else unassignedLines.add(row);
        }

        List<Map<String, Object>> scopes = new ArrayList<>();
        for (Map.Entry<Long, String> e : scopeNames.entrySet()) {
            List<Map<String, Object>> rows = linesByScope.get(e.getKey());
            if (rows.isEmpty()) continue;   // a scope with no materials has nothing to compare
            scopes.add(scopeGroup(e.getKey(), e.getValue(), rows, canSeeRates));
        }
        if (!unassignedLines.isEmpty()) {
            scopes.add(scopeGroup(null, "Unassigned", unassignedLines, canSeeRates));
        }

        // ── Totals. Summed off the same rows the screen shows, so they tie. ──
        BigDecimal plannedExGst  = BigDecimal.ZERO, procuredExGst = BigDecimal.ZERO;
        BigDecimal plannedQtyGst = BigDecimal.ZERO, procuredGst   = BigDecimal.ZERO;
        for (ProjectBomEntity b : bom) {
            plannedExGst  = plannedExGst.add(plannedAmount(b));
            plannedQtyGst = plannedQtyGst.add(gstOf(plannedAmount(b), b.getGstPercent()));
        }
        for (AttributedPoLine l : poLines) {
            if (l.bomLineId() == null || !bomById.containsKey(l.bomLineId())) continue;
            procuredExGst = procuredExGst.add(l.row().amount());
            procuredGst   = procuredGst.add(gstOf(l.row().amount(), l.row().taxPercent()));
        }

        BigDecimal unattributedExGst = BigDecimal.ZERO, unattributedGst = BigDecimal.ZERO;
        for (AttributedPoLine l : unattributed) {
            unattributedExGst = unattributedExGst.add(l.row().amount());
            unattributedGst   = unattributedGst.add(gstOf(l.row().amount(), l.row().taxPercent()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canSeeRates", canSeeRates);
        data.put("scopes", scopes);
        data.put("bomLineCount", bom.size());
        data.put("poLineCount", poLines.size());

        // Summary — planned, procured and variance INCLUDING GST, so it can be read
        // before any scrolling.
        Map<String, Object> summary = new LinkedHashMap<>();
        if (canSeeRates) {
            BigDecimal plannedInc  = plannedExGst.add(plannedQtyGst);
            BigDecimal procuredInc = procuredExGst.add(procuredGst);
            summary.put("plannedIncGst",  money(plannedInc));
            summary.put("procuredIncGst", money(procuredInc));
            summary.put("varianceIncGst", money(procuredInc.subtract(plannedInc)));
            summary.put("variancePct",    pct(procuredInc.subtract(plannedInc), plannedInc));
        }
        summary.put("notOrderedLineCount", (int) bom.stream()
                .filter(b -> feeding.get(b.getId()).isEmpty()).count());
        summary.put("overBomLineCount", (int) bom.stream()
                .filter(b -> procuredQty(feeding.get(b.getId())).compareTo(nz(b.getQuantity())) > 0)
                .count());
        data.put("summary", summary);

        data.put("gstSummary", gstSummary(bom, feeding, canSeeRates));
        data.put("unattributed", unattributedBlock(unattributed, canSeeRates));
        data.put("purchaseOrders", poGroups(poLines, bomById, scopeNames, canSeeRates));

        // Reconciliation: BOM procured + unattributed must equal the project's total
        // procurement. Two figures that fail to tie on one screen destroy confidence
        // in both, so the arithmetic is published rather than left to the reader.
        if (canSeeRates) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("bomProcuredExGst",   money(procuredExGst));
            rec.put("unattributedExGst",  money(unattributedExGst));
            rec.put("projectProcuredExGst", money(procuredExGst.add(unattributedExGst)));
            rec.put("bomProcuredIncGst",  money(procuredExGst.add(procuredGst)));
            rec.put("unattributedIncGst", money(unattributedExGst.add(unattributedGst)));
            rec.put("projectProcuredIncGst",
                    money(procuredExGst.add(procuredGst).add(unattributedExGst).add(unattributedGst)));
            data.put("reconciliation", rec);
        }
        return data;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Row builders
    // ═════════════════════════════════════════════════════════════════════════

    /** One BOM line with what was procured against it, and the POs that did so. */
    private Map<String, Object> bomLineRow(ProjectBomEntity b, List<AttributedPoLine> feeds,
                                           boolean canSeeRates) {
        BigDecimal plannedQty  = nz(b.getQuantity());
        BigDecimal procuredQty = procuredQty(feeds);
        BigDecimal plannedAmt  = plannedAmount(b);
        BigDecimal procuredAmt = BigDecimal.ZERO;
        for (AttributedPoLine l : feeds) procuredAmt = procuredAmt.add(l.row().amount());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bomLineId", b.getId());
        m.put("seqNo", b.getSeqNo());
        m.put("itemName", b.getItemName());
        m.put("make", b.getMake());
        m.put("specification", b.getSpecification());
        m.put("unit", b.getUnit());
        m.put("category", b.getCategory());
        m.put("bomItemId", b.getBomItemId());
        m.put("gstPercent", b.getGstPercent());
        m.put("plannedQty", qty(plannedQty));
        m.put("procuredQty", qty(procuredQty));
        m.put("qtyVariance", qty(procuredQty.subtract(plannedQty)));
        // An explicit state, not a blank: a planned item nobody bought is one of the
        // most useful things this screen reports.
        m.put("notOrdered", feeds.isEmpty());
        // These exist despite the blocking rules — legacy POs predate enforcement, and
        // a BOM quantity can be reduced after ordering.
        m.put("overBom", procuredQty.compareTo(plannedQty) > 0);
        m.put("poCount", feeds.stream().map(f -> f.row().poId()).distinct().count());
        if (canSeeRates) {
            m.put("plannedRate", money(nz(b.getUnitRate())));
            m.put("plannedAmount", money(plannedAmt));
            m.put("procuredAmount", money(procuredAmt));
            m.put("variance", money(procuredAmt.subtract(plannedAmt)));
            m.put("variancePct", pct(procuredAmt.subtract(plannedAmt), plannedAmt));
        }
        // One BOM line commonly draws on several POs at different rates; a single
        // averaged rate would hide exactly that.
        List<Map<String, Object>> pos = new ArrayList<>();
        for (AttributedPoLine l : feeds) pos.add(poFeedRow(l, canSeeRates));
        m.put("pos", pos);
        return m;
    }

    /** One PO line as it appears under the BOM line it feeds. */
    private Map<String, Object> poFeedRow(AttributedPoLine l, boolean canSeeRates) {
        PoLineRow r = l.row();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poId", r.poId());
        m.put("poNo", r.poNo());
        m.put("poRefId", r.poRefId());
        m.put("vendorId", r.vendorId());
        m.put("vendorName", r.vendorName());
        m.put("orderDate", r.orderDate() != null ? r.orderDate().toString() : null);
        m.put("status", r.status());
        m.put("lineNo", r.lineNo());
        m.put("itemName", r.itemName());
        m.put("make", r.make());
        m.put("unit", r.unit());
        m.put("quantity", qty(nz(r.quantity())));
        m.put("gstPercent", r.taxPercent());
        // How this line reached the BOM line, so an inferred match is never mistaken
        // for a picked one, and a pre-enforcement row says so.
        m.put("match", l.match() != null ? l.match().name() : null);
        m.put("legacy", r.legacy());
        if (canSeeRates) {
            m.put("rate", money(nz(r.unitPrice())));
            m.put("amount", money(r.amount()));
        }
        return m;
    }

    /** A scope group with its rollup. Totals are summed from the rows it contains. */
    private Map<String, Object> scopeGroup(Long scopeItemId, String name,
                                          List<Map<String, Object>> rows, boolean canSeeRates) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("scopeItemId", scopeItemId);
        g.put("scopeName", name);
        g.put("itemCount", rows.size());
        g.put("notOrderedCount", (int) rows.stream().filter(r -> Boolean.TRUE.equals(r.get("notOrdered"))).count());
        g.put("overBomCount", (int) rows.stream().filter(r -> Boolean.TRUE.equals(r.get("overBom"))).count());
        if (canSeeRates) {
            BigDecimal planned = BigDecimal.ZERO, procured = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) {
                planned  = planned.add(dec(r.get("plannedAmount")));
                procured = procured.add(dec(r.get("procuredAmount")));
            }
            g.put("plannedAmount", money(planned));
            g.put("procuredAmount", money(procured));
            g.put("variance", money(procured.subtract(planned)));
            g.put("variancePct", pct(procured.subtract(planned), planned));
        }
        g.put("lines", rows);
        return g;
    }

    /**
     * Planned and procured GST side by side, one row per slab present on EITHER side.
     *
     * <p>A slab present on only one side still emits both columns with a zero rather
     * than a blank: a vendor billing 18% on an item planned at 5% is exactly what this
     * block exists to surface, and a blank reads as missing data instead of a finding.
     */
    private Map<String, Object> gstSummary(List<ProjectBomEntity> bom,
                                           Map<Long, List<AttributedPoLine>> feeding,
                                           boolean canSeeRates) {
        // Key: the slab as a plain string; null slab → the no-rate bucket.
        Map<String, BigDecimal[]> slabs = new LinkedHashMap<>();   // [plannedTaxable, plannedGst, procuredTaxable, procuredGst]
        BigDecimal[] noRate = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};

        for (ProjectBomEntity b : bom) {
            BigDecimal amt = plannedAmount(b);
            if (b.getGstPercent() == null) {
                // No catalogue reference ⇒ no default rate. Never a silent 0%.
                noRate[0] = noRate[0].add(amt);
            } else {
                BigDecimal[] s = slabs.computeIfAbsent(slabKey(b.getGstPercent()), k -> zero4());
                s[0] = s[0].add(amt);
                s[1] = s[1].add(gstOf(amt, b.getGstPercent()));
            }
            for (AttributedPoLine l : feeding.getOrDefault(b.getId(), List.of())) {
                BigDecimal pAmt = l.row().amount();
                BigDecimal rate = l.row().taxPercent();
                if (rate == null) {
                    noRate[2] = noRate[2].add(pAmt);
                } else {
                    BigDecimal[] s = slabs.computeIfAbsent(slabKey(rate), k -> zero4());
                    s[2] = s[2].add(pAmt);
                    s[3] = s[3].add(gstOf(pAmt, rate));
                }
            }
        }

        List<String> keys = new ArrayList<>(slabs.keySet());
        keys.sort((a, b) -> new BigDecimal(a).compareTo(new BigDecimal(b)));

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal pSub = BigDecimal.ZERO, pGst = BigDecimal.ZERO,
                   aSub = BigDecimal.ZERO, aGst = BigDecimal.ZERO;

        for (String k : keys) {
            BigDecimal[] s = slabs.get(k);
            pSub = pSub.add(s[0]); pGst = pGst.add(s[1]);
            aSub = aSub.add(s[2]); aGst = aGst.add(s[3]);
            rows.add(slabRow(k + "%", s, canSeeRates));
        }
        boolean anyNoRate = noRate[0].signum() != 0 || noRate[2].signum() != 0;
        if (anyNoRate) {
            pSub = pSub.add(noRate[0]);
            aSub = aSub.add(noRate[2]);
            rows.add(slabRow("No GST rate available", noRate, canSeeRates));
        }
        out.put("rows", rows);
        if (canSeeRates) {
            out.put("plannedSubtotal",  money(pSub));
            out.put("procuredSubtotal", money(aSub));
            out.put("plannedGst",       money(pGst));
            out.put("procuredGst",      money(aGst));
            out.put("plannedTotal",     money(pSub.add(pGst)));
            out.put("procuredTotal",    money(aSub.add(aGst)));
            out.put("subtotalVariance", money(aSub.subtract(pSub)));
            out.put("gstVariance",      money(aGst.subtract(pGst)));
            out.put("totalVariance",    money(aSub.add(aGst).subtract(pSub.add(pGst))));
        }
        return out;
    }

    private Map<String, Object> slabRow(String label, BigDecimal[] s, boolean canSeeRates) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("label", label);
        if (canSeeRates) {
            r.put("plannedTaxable",  money(s[0]));
            r.put("plannedGst",      money(s[1]));
            r.put("procuredTaxable", money(s[2]));
            r.put("procuredGst",     money(s[3]));
            r.put("gstVariance",     money(s[3].subtract(s[1])));
        }
        return r;
    }

    /**
     * POs on this project carrying no BOM link — legacy rows, and any line that could
     * not be resolved. Listed rather than dropped, because the reconciliation at the
     * foot of the comparison only ties if these are visible.
     */
    private Map<String, Object> unattributedBlock(List<AttributedPoLine> unattributed,
                                                  boolean canSeeRates) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal exGst = BigDecimal.ZERO;
        for (AttributedPoLine l : unattributed) {
            rows.add(poFeedRow(l, canSeeRates));
            exGst = exGst.add(l.row().amount());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", rows);
        out.put("lineCount", rows.size());
        out.put("poCount", unattributed.stream().map(l -> l.row().poId()).distinct().count());
        if (canSeeRates) out.put("totalExGst", money(exGst));
        return out;
    }

    /**
     * Procurement grouped BY PURCHASE ORDER — the one view the comparison table cannot
     * express. Vendor and item groupings are the same rows regrouped, which the client
     * does; the server ships them once.
     */
    private List<Map<String, Object>> poGroups(List<AttributedPoLine> poLines,
                                               Map<Long, ProjectBomEntity> bomById,
                                               Map<Long, String> scopeNames,
                                               boolean canSeeRates) {
        Map<Long, List<AttributedPoLine>> byPo = new LinkedHashMap<>();
        for (AttributedPoLine l : poLines) {
            byPo.computeIfAbsent(l.row().poId(), k -> new ArrayList<>()).add(l);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, List<AttributedPoLine>> e : byPo.entrySet()) {
            List<AttributedPoLine> ls = e.getValue();
            PoLineRow h = ls.get(0).row();
            BigDecimal exGst = BigDecimal.ZERO, gst = BigDecimal.ZERO;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AttributedPoLine l : ls) {
                Map<String, Object> r = poFeedRow(l, canSeeRates);
                ProjectBomEntity b = l.bomLineId() != null ? bomById.get(l.bomLineId()) : null;
                // A line with no BOM link is LABELLED as such, never omitted.
                r.put("bomLineId", l.bomLineId());
                r.put("bomItemName", b != null ? b.getItemName() : null);
                r.put("bomMake", b != null ? b.getMake() : null);
                r.put("bomScopeName", b != null && b.getScopeItemId() != null
                        ? scopeNames.getOrDefault(b.getScopeItemId(), "Unassigned")
                        : (b != null ? "Unassigned" : null));
                rows.add(r);
                exGst = exGst.add(l.row().amount());
                gst   = gst.add(gstOf(l.row().amount(), l.row().taxPercent()));
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("poId", h.poId());
            g.put("poNo", h.poNo());
            g.put("poRefId", h.poRefId());
            g.put("vendorId", h.vendorId());
            g.put("vendorName", h.vendorName());
            g.put("orderDate", h.orderDate() != null ? h.orderDate().toString() : null);
            g.put("status", h.status());
            g.put("lineCount", rows.size());
            g.put("unlinkedLineCount", (int) ls.stream().filter(l -> l.bomLineId() == null).count());
            if (canSeeRates) {
                g.put("totalExGst", money(exGst));
                g.put("totalIncGst", money(exGst.add(gst)));
            }
            g.put("lines", rows);
            out.add(g);
        }
        return out;
    }

    // ── arithmetic helpers ───────────────────────────────────────────────────

    private static BigDecimal procuredQty(List<AttributedPoLine> feeds) {
        BigDecimal t = BigDecimal.ZERO;
        for (AttributedPoLine l : feeds) t = t.add(nz(l.row().quantity()));
        return t;
    }

    /**
     * Planned ex-GST amount. The stored {@code amount} is authoritative — lump-sum
     * lines carry an amount with no quantity or rate — and only falls back to
     * qty × rate when it was never written.
     */
    private static BigDecimal plannedAmount(ProjectBomEntity b) {
        if (b.getAmount() != null && b.getAmount().signum() != 0) return b.getAmount();
        return nz(b.getQuantity()).multiply(nz(b.getUnitRate()));
    }

    private static BigDecimal gstOf(BigDecimal amount, BigDecimal ratePct) {
        if (amount == null || ratePct == null) return BigDecimal.ZERO;
        return amount.multiply(ratePct).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String slabKey(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal[] zero4() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal qty(BigDecimal v) {
        return nz(v).setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** Variance as a percentage of planned. Null when there is nothing to compare to. */
    private static BigDecimal pct(BigDecimal delta, BigDecimal base) {
        if (base == null || base.signum() == 0) return null;
        return delta.multiply(HUNDRED).divide(base, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }
}
