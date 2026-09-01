package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.entity.SiteVisitEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectPhaseEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectScopeEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.SiteVisitRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectPhaseRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectScopeRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.PhaseRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.ScopeRequest;

/**
 * Seeds a newly auto-created project's Technical Scope and BOM from the lead behind
 * the order book's proposal.
 *
 * <p>Runs exactly once, at project creation, and never touches a project that already
 * has scope lines. Nothing here writes to the order book's own scope/phases/BOM —
 * that work lives on the project now, and {@code order_book_scope} is left alone so
 * the Overview tab's site location keeps working.
 *
 * <p><b>Why this is its own bean, and why it runs after commit.</b>
 * {@code OrderBookService.autoCreateProjectForOrderBook} is annotated
 * {@code REQUIRES_NEW} but called via {@code this.}, so its proxy is bypassed and it
 * actually runs in the order book's transaction — the project row is still
 * uncommitted when it returns. A separate bean makes {@link Propagation#REQUIRES_NEW}
 * below genuinely apply, which is what stops a failed import rolling back the order
 * book and makes the import all-or-nothing (never BOM lines without scope lines).
 *
 * <p>But that isolation cuts both ways: a new transaction cannot see the caller's
 * uncommitted project either, so calling this inline threw "Project not found" every
 * time. The caller therefore defers it to {@code afterCommit}, once the order book and
 * its project are both durable.
 */
@Service
public class ProjectLeadSeedService {

    private static final Logger log = LoggerFactory.getLogger(ProjectLeadSeedService.class);

    /** Matches {@code ProjectDetailService.PHASE_WEIGHT_SCALE}. */
    private static final int WEIGHT_SCALE = 6;
    private static final BigDecimal WEIGHT_TOTAL = new BigDecimal("100");

    /** Marks a project scope as copied from a lead rather than generated from a template. */
    public static final String SCOPE_SOURCE_LEAD_COPY = "LEAD_COPY";

    @Autowired private OrderBookRepo orderBookRepo;
    @Autowired private DropdownProjectRepository projectRepository;
    @Autowired private SiteVisitRepo siteVisitRepo;
    @Autowired private LeadsRepo leadsRepo;
    @Autowired private LeadScopeRepo leadScopeRepo;
    @Autowired private LeadScopeItemRepo leadScopeItemRepo;
    @Autowired private LeadBomRepo leadBomRepo;
    @Autowired private LeadScopeTemplateRepo leadScopeTemplateRepo;
    @Autowired private LeadScopeTemplateItemRepo leadScopeTemplateItemRepo;
    @Autowired private ProjectPhaseRepo phaseRepo;
    @Autowired private ProjectScopeRepo scopeRepo;
    @Autowired private ProjectDetailService projectDetailService;

    /**
     * Copies the lead's scope and BOM onto a freshly created project.
     *
     * <p>Returns quietly — no error, no warning — when there is nothing to import:
     * no lead on the order book (the proposal had none, or there was no proposal),
     * or the lead has no scope lines. Those are normal states; the project starts
     * empty and the manual / Suggest-from-template flow applies.
     *
     * @throws Exception on a genuine failure, so the caller can log it. The whole
     *         import rolls back with it and the scope-origin marker stays unset,
     *         which keeps Suggest available to the PM.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seedFromLead(Long orderBookId, String projectUniqueId, Long userId) throws Exception {

        // Loaded here, inside this transaction, rather than handed in as entities.
        // The caller runs this AFTER its own transaction commits, so both rows are
        // visible; entities detached from that transaction would not be.
        OrderBookEntity orderBook = orderBookRepo.findById(orderBookId).orElse(null);
        if (orderBook == null) return;

        DropdownProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId).orElse(null);
        if (project == null) {
            log.warn("Project {} not found — nothing imported for order book {}",
                projectUniqueId, orderBook.getOrderBookNo());
            return;
        }

        Long leadId = orderBook.getLeadId();
        if (leadId == null) return;                                   // no proposal, or proposal has no lead

        Long projectId = project.getId();
        if (!phaseRepo.findByProjectIdOrderBySeqNo(projectId).isEmpty()) {
            log.info("Skipping lead import for project {} — it already has scope lines",
                project.getProjectUniqueId());
            return;                                                   // import runs once, never overwrites
        }

        List<LeadScopeItemEntity> leadScope =
            leadScopeItemRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        if (leadScope.isEmpty()) {
            // Deliberately import nothing at all, even if the lead has BOM lines: a
            // project with BOM lines and no scope lines is worse than an empty one.
            log.info("Lead {} has no scope lines — nothing imported for project {}",
                leadId, project.getProjectUniqueId());
            return;
        }

        LeadsEntity lead = leadsRepo.findById(leadId).orElse(null);
        if (lead == null) {
            log.warn("Order book {} points at lead {}, which no longer exists — nothing imported",
                orderBook.getOrderBookNo(), leadId);
            return;
        }

        LeadScopeTemplateEntity template = resolveActiveTemplate(lead.getSubGroupName());

        // ── Scope ────────────────────────────────────────────────────────────
        // Built as a ScopeRequest and handed to the existing saveScope so the import
        // reuses the project's own weight normalisation and phase-insert path rather
        // than reimplementing either. A set produced here therefore can never be
        // rejected by the scope tab's own save validation.
        // Read the BOM before building the phases: its per-scope-line totals become
        // each phase's planned budget, so both go in with one write.
        List<LeadBomEntity> leadBom =
            leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);

        ScopeRequest scopeReq = new ScopeRequest();
        scopeReq.setProjectType(lead.getSubGroupName());
        scopeReq.setSystemCapacity(resolveSystemCapacity(lead, leadId));
        applySiteLocation(scopeReq, lead, leadId);
        scopeReq.setPhases(buildPhases(leadScope, template, rollUpBomCost(leadBom)));
        projectDetailService.saveScope(project.getProjectUniqueId(), scopeReq, userId);

        // ── BOM ──────────────────────────────────────────────────────────────
        // Re-read the phases we just created to map each lead scope line to its new
        // project scope line. saveScope inserted them in request order, and this
        // comes back ordered by seq_no, so index alignment holds.
        List<ProjectPhaseEntity> newPhases = phaseRepo.findByProjectIdOrderBySeqNo(projectId);
        Map<Long, Long> phaseIdByLeadScopeItemId = new HashMap<>();
        for (int i = 0; i < leadScope.size() && i < newPhases.size(); i++) {
            phaseIdByLeadScopeItemId.put(leadScope.get(i).getId(), newPhases.get(i).getId());
        }

        if (!leadBom.isEmpty()) {
            BomSaveRequest bomReq = new BomSaveRequest();
            bomReq.setLines(buildBomLines(leadBom, phaseIdByLeadScopeItemId));
            projectDetailService.saveBom(project.getProjectUniqueId(), bomReq, userId);
        }

        // ── Provenance, written LAST ─────────────────────────────────────────
        // Only a fully successful import is marked. If anything above threw, the
        // whole transaction rolls back and scope_source stays unset, so Suggest
        // remains available instead of the PM being stranded.
        ProjectScopeEntity scope = scopeRepo.findByProjectId(projectId).orElse(null);
        if (scope != null) {
            scope.setScopeSource(SCOPE_SOURCE_LEAD_COPY);
            scope.setSourceTemplateId(template != null ? template.getId() : null);
            scope.setTemplateVersion(template != null ? template.getVersion() : null);
            scopeRepo.save(scope);
        }

        BigDecimal allocated = rollUpBomCost(leadBom).values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("Imported lead {} into project {} | scopeLines={} | bomLines={} | allocated={} | template={}",
            leadId, project.getProjectUniqueId(), leadScope.size(), leadBom.size(), allocated,
            template != null ? template.getId() + "v" + template.getVersion() : "none");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Scope
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One parent phase per lead scope line, in order, with no sub-items — the PM adds
     * those afterwards, so a matched template line's sub-items are deliberately not
     * imported.
     *
     * <p>Lead scope lines have no category / quantity / unit counterpart on
     * {@code project_phases}; those three are dropped. Quantities still reach the
     * project in full via the BOM import, which is where they are actually modelled.
     */
    private List<PhaseRequest> buildPhases(List<LeadScopeItemEntity> leadScope,
                                           LeadScopeTemplateEntity template,
                                           Map<Long, BigDecimal> costByLeadScopeItemId) {
        List<BigDecimal> weights = deriveWeights(leadScope, template);
        List<PhaseRequest> phases = new ArrayList<>();
        for (int i = 0; i < leadScope.size(); i++) {
            LeadScopeItemEntity src = leadScope.get(i);
            PhaseRequest p = new PhaseRequest();
            p.setId(null);
            p.setSeqNo(i + 1);
            p.setPhaseName(src.getActivity());
            p.setPhaseDescription(joinDescription(src.getSpecification(), src.getNotes()));
            p.setStatus("Not Started");
            p.setProgressPercent(BigDecimal.ZERO);
            p.setWeightPct(weights.get(i));
            p.setPlannedBudget(costByLeadScopeItemId.get(src.getId()));
            p.setSubItems(null);
            phases.add(p);
        }
        return phases;
    }

    /**
     * Cost to procure per scope line: the lead BOM's own amounts, summed by the scope
     * line each material sits under. This is what fills the project's Budget
     * Allocation block, and it comes from the same rows the BOM tab shows, so the two
     * can never disagree.
     *
     * <p>The lead's Budget tab is deliberately not the source — it is keyed by
     * category ({@code lead_budget} → {@code lead_budget_items}), with no link to a
     * scope line, so there is nothing to map these rows onto.
     *
     * <p>BOM lines with no scope line (the "General" bucket) are left out rather than
     * charged to an arbitrary phase; the Budget Allocation block's "Add extra item"
     * row is where that cost belongs if it needs to be carried.
     */
    private Map<Long, BigDecimal> rollUpBomCost(List<LeadBomEntity> leadBom) {
        Map<Long, BigDecimal> byScopeItem = new HashMap<>();
        for (LeadBomEntity line : leadBom) {
            if (line.getScopeItemId() == null) continue;
            byScopeItem.merge(line.getScopeItemId(), lineAmount(line), BigDecimal::add);
        }
        return byScopeItem;
    }

    /** The line's stored amount, or quantity x rate when it was never computed. */
    private BigDecimal lineAmount(LeadBomEntity line) {
        if (line.getAmount() != null && line.getAmount().signum() != 0) return line.getAmount();
        BigDecimal qty  = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ZERO;
        BigDecimal rate = line.getUnitRate() != null ? line.getUnitRate() : BigDecimal.ZERO;
        return qty.multiply(rate);
    }

    /** Specification and notes both land in the phase's one description field. */
    private String joinDescription(String specification, String notes) {
        boolean hasSpec  = specification != null && !specification.isBlank();
        boolean hasNotes = notes != null && !notes.isBlank();
        if (hasSpec && hasNotes) return specification.trim() + "\nNote: " + notes.trim();
        if (hasSpec)  return specification.trim();
        if (hasNotes) return notes.trim();
        return null;
    }

    /**
     * Weights for imported scope lines. Lead scope carries none by design, but project
     * scope requires them and the set must total 100.
     *
     * <ol>
     *   <li>Match each line to a line of the sub-group's active template by activity
     *       text, trimmed and case-insensitive; a match takes that template weight.</li>
     *   <li>Unmatched lines take the mean of the matched weights. If nothing matched
     *       at all, split evenly across every line.</li>
     *   <li>Scale the set to 100 here, then let
     *       {@code ProjectDetailService.normalisePhaseWeights} absorb the last
     *       sub-0.005 sliver onto the largest line — the project's own rule, not a
     *       new one. Pre-scaling matters: a lead with more lines than the template
     *       would otherwise sum well past 100 and be rejected outright.</li>
     * </ol>
     *
     * <p>The lead's scope lines do not record which template version they came from,
     * so matching is against whatever template is active now. Accepted.
     */
    private List<BigDecimal> deriveWeights(List<LeadScopeItemEntity> leadScope,
                                           LeadScopeTemplateEntity template) {
        List<String> activities = new ArrayList<>(leadScope.size());
        for (LeadScopeItemEntity item : leadScope) activities.add(item.getActivity());
        return computeWeights(activities, templateWeightsByActivity(template));
    }

    /**
     * The derivation itself, free of Spring and JPA so it can be tested directly.
     *
     * @param activities       imported scope lines' activity text, in order
     * @param weightByActivity the active template's weights, keyed by {@link #activityKey}
     */
    static List<BigDecimal> computeWeights(List<String> activities,
                                           Map<String, BigDecimal> weightByActivity) {
        int n = activities.size();
        if (n == 0) return new ArrayList<>();

        List<BigDecimal> raw = new ArrayList<>(java.util.Collections.nCopies(n, (BigDecimal) null));
        BigDecimal matchedSum = BigDecimal.ZERO;
        int matchedCount = 0;
        for (int i = 0; i < n; i++) {
            BigDecimal w = weightByActivity.get(activityKey(activities.get(i)));
            if (w != null) {
                raw.set(i, w);
                matchedSum = matchedSum.add(w);
                matchedCount++;
            }
        }

        if (matchedCount == 0) return evenSplit(n);

        BigDecimal mean = matchedSum.divide(BigDecimal.valueOf(matchedCount), WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            if (raw.get(i) == null) raw.set(i, mean);
            total = total.add(raw.get(i));
        }
        if (total.signum() <= 0) return evenSplit(n);

        // Scale to 100. saveScope's normalisation then puts the rounding remainder on
        // the largest line, which is the project's existing rule.
        List<BigDecimal> scaled = new ArrayList<>(n);
        for (BigDecimal w : raw) {
            scaled.add(w.multiply(WEIGHT_TOTAL).divide(total, WEIGHT_SCALE, RoundingMode.HALF_UP));
        }
        return scaled;
    }

    /** Activity text of the active template's lines → its weight. First wins on duplicates. */
    private Map<String, BigDecimal> templateWeightsByActivity(LeadScopeTemplateEntity template) {
        Map<String, BigDecimal> byActivity = new HashMap<>();
        if (template == null) return byActivity;
        List<LeadScopeTemplateItemEntity> lines =
            leadScopeTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(template.getId());
        for (LeadScopeTemplateItemEntity line : lines) {
            String key = activityKey(line.getActivity());
            // A null or non-positive template weight is no weight at all — leaving the
            // line unmatched lets it take the mean instead of dragging the set to zero.
            if (key == null || line.getWeightPct() == null || line.getWeightPct().signum() <= 0) continue;
            byActivity.putIfAbsent(key, line.getWeightPct());
        }
        return byActivity;
    }

    static String activityKey(String activity) {
        if (activity == null) return null;
        String t = activity.trim();
        return t.isEmpty() ? null : t.toLowerCase();
    }

    private static List<BigDecimal> evenSplit(int n) {
        List<BigDecimal> out = new ArrayList<>(n);
        BigDecimal each = WEIGHT_TOTAL.divide(BigDecimal.valueOf(n), WEIGHT_SCALE, RoundingMode.HALF_UP);
        for (int i = 0; i < n; i++) out.add(each);
        return out;
    }

    private LeadScopeTemplateEntity resolveActiveTemplate(String subGroupName) {
        if (subGroupName == null || subGroupName.isBlank()) return null;
        return leadScopeTemplateRepo
            .findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(subGroupName)
            .orElse(null);
    }

    /**
     * The project's system capacity, taken from the lead.
     *
     * <p>Not cosmetic: {@code ProjectDetailService.getBom} parses this into
     * {@code capacityKw}, and the BOM tab re-derives every auto-sized quantity from it
     * on load. Leave it null and an imported BOM's quantities blank out on first view.
     */
    private String resolveSystemCapacity(LeadsEntity lead, Long leadId) {
        LeadScopeEntity leadScopeHeader = leadScopeRepo.findByLeadIdAndDeletedAtIsNull(leadId).orElse(null);
        if (leadScopeHeader != null
                && leadScopeHeader.getSystemCapacity() != null
                && !leadScopeHeader.getSystemCapacity().isBlank()) {
            return leadScopeHeader.getSystemCapacity().trim();
        }
        if (lead.getCapacity() == null || lead.getCapacity().isBlank()) return null;
        String unit = lead.getCapacityUnit();
        return unit == null || unit.isBlank()
            ? lead.getCapacity().trim()
            : lead.getCapacity().trim() + " " + unit.trim();
    }

    /**
     * The project's site, taken from the lead. Sets the address and, when the lead was
     * pinned on a map, the coordinates too — so the project Overview shows the site
     * straight away instead of the PM re-entering an address the surveyor already
     * captured.
     *
     * <p>Preference order: the site visit (the surveyed address, and the only source
     * with coordinates) → the lead's scope header → the lead's own location/city.
     * Latest visit wins.
     */
    private void applySiteLocation(ScopeRequest scopeReq, LeadsEntity lead, Long leadId) {
        List<SiteVisitEntity> visits = siteVisitRepo.findByLeadIdOrderByVisitDateDescIdDesc(leadId);
        for (SiteVisitEntity v : visits) {
            if (v.getSiteAddress() == null || v.getSiteAddress().isBlank()) continue;
            scopeReq.setSiteLocation(v.getSiteAddress().trim());
            if (v.getSiteLat() != null) scopeReq.setSiteLat(BigDecimal.valueOf(v.getSiteLat()));
            if (v.getSiteLng() != null) scopeReq.setSiteLng(BigDecimal.valueOf(v.getSiteLng()));
            return;
        }

        LeadScopeEntity header = leadScopeRepo.findByLeadIdAndDeletedAtIsNull(leadId).orElse(null);
        if (header != null && header.getSiteLocation() != null && !header.getSiteLocation().isBlank()) {
            scopeReq.setSiteLocation(header.getSiteLocation().trim());
            return;
        }

        String fallback = leadAddress(lead);
        if (!fallback.isEmpty()) scopeReq.setSiteLocation(fallback);
    }

    /** The lead's own location, or failing that whatever of city/state/pincode is set. */
    private String leadAddress(LeadsEntity lead) {
        if (lead.getTcLocation() != null && !lead.getTcLocation().isBlank()) {
            return lead.getTcLocation().trim();
        }
        List<String> parts = new ArrayList<>();
        if (lead.getCity()    != null && !lead.getCity().isBlank())    parts.add(lead.getCity().trim());
        if (lead.getState()   != null && !lead.getState().isBlank())   parts.add(lead.getState().trim());
        if (lead.getPincode() != null && !lead.getPincode().isBlank()) parts.add(lead.getPincode().trim());
        return String.join(", ", parts);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BOM
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Every lead BOM line, carrying item, make/variant, catalogue linkage, spec, unit,
     * quantity, the quantity-derivation basis with its values, and the unit rate (which
     * stays editable on the project).
     *
     * <p>{@code scopeSubItemKey} is left empty: no sub-items are imported, so there is
     * nothing for it to point at.
     */
    private List<BomLineRequest> buildBomLines(List<LeadBomEntity> leadBom,
                                               Map<Long, Long> phaseIdByLeadScopeItemId) {
        List<BomLineRequest> lines = new ArrayList<>();
        int seq = 1;
        for (LeadBomEntity src : leadBom) {
            BomLineRequest l = new BomLineRequest();
            l.setId(null);
            l.setSeqNo(seq++);
            // Point at the newly created project scope line, never at the lead-side id.
            l.setScopeItemId(src.getScopeItemId() == null
                ? null : phaseIdByLeadScopeItemId.get(src.getScopeItemId()));
            l.setScopeSubItemKey(null);
            l.setCategory(src.getCategory());
            l.setItemName(src.getItemName());
            l.setMake(src.getMake());
            l.setSpecification(src.getSpecification());
            l.setBomItemId(src.getBomItemId());
            l.setVariantId(src.getVariantId());
            l.setUnit(src.getUnit());
            l.setQuantity(src.getQuantity());
            l.setUnitRate(src.getUnitRate());
            l.setAmount(src.getAmount());
            l.setNotes(src.getNotes());
            // Quantity-derivation basis and its values, so the project BOM stays live.
            l.setBasis(src.getBasis());
            l.setBasisValue(src.getBasisValue());
            l.setStepValue(src.getStepValue());
            l.setSiteVisitField(src.getSiteVisitField());
            l.setDriverAttr(src.getDriverAttr());
            l.setAutoQty(src.getAutoQty());
            lines.add(l);
        }
        return lines;
    }
}
