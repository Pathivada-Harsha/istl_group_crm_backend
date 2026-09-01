package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.TemplateLineVariantEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.repo.TemplateLineVariantRepo;
import com.istlgroup.istl_group_crm_backend.util.VariantAttributes;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateBomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateBomLinesRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeLinesRequest;

import lombok.RequiredArgsConstructor;

/**
 * CRUD for the Scope/BOM templates behind the "Suggest scope / BOM" feature.
 * Two-step soft delete (active → deactivate, inactive → hard delete) mirrors
 * {@link DropdownAdminService}. Line lists are whole-list replaces (soft-delete
 * the absent, upsert the rest) mirroring LeadScopeService.
 */
@Service
@RequiredArgsConstructor
public class LeadAdminService {

    private final LeadScopeTemplateRepo templateRepo;
    private final LeadScopeTemplateItemRepo scopeItemRepo;
    private final LeadBomTemplateItemRepo bomItemRepo;
    private final BomItemVariantRepo bomItemVariantRepo;
    private final BomItemsMasterRepo bomItemsMasterRepo;
    private final TemplateLineVariantRepo templateLineVariantRepo;
    private final BomCatalogueHealthService catalogueHealth;

    // ── Header ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LeadScopeTemplateEntity t : templateRepo.findByDeletedAtIsNullOrderByProjectTypeAscIdAsc()) {
            out.add(headerMap(t));
        }
        return out;
    }

    public Map<String, Object> listTemplatesPaged(String search, int page, int size) {
        Page<LeadScopeTemplateEntity> p = templateRepo
                .findByDeletedAtIsNullAndProjectTypeContainingIgnoreCase(
                        search == null ? "" : search, PageRequest.of(page, size));
        List<Map<String, Object>> content = new ArrayList<>();
        for (LeadScopeTemplateEntity t : p.getContent()) content.add(headerMap(t));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("content", content);
        res.put("totalElements", p.getTotalElements());
        res.put("totalPages", p.getTotalPages());
        res.put("size", p.getSize());
        res.put("number", p.getNumber());
        return res;
    }

    /** Full template with its scope + BOM lines — what the admin editor loads. */
    public Map<String, Object> getTemplate(Long id) throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Template not found"));

        Map<String, Object> m = headerMap(t);
        List<Map<String, Object>> scope = new ArrayList<>();
        for (LeadScopeTemplateItemEntity s : scopeItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(id)) {
            scope.add(scopeLineMap(s));
        }
        List<Map<String, Object>> bom = new ArrayList<>();
        for (LeadBomTemplateItemEntity b : bomItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(id)) {
            bom.add(bomLineMap(b));
        }
        m.put("scopeItems", scope);
        m.put("bomItems", bom);
        return m;
    }

    @Transactional
    public Map<String, Object> createTemplate(TemplateHeaderRequest body, Long userId) throws CustomException {
        if (body.getProjectType() == null || body.getProjectType().isBlank()) {
            throw new CustomException("Project type is required");
        }
        LeadScopeTemplateEntity t = new LeadScopeTemplateEntity();
        t.setProjectType(body.getProjectType().trim());
        t.setName(body.getName());
        t.setDescription(body.getDescription());
        t.setIsActive(body.getIsActive() == null ? Boolean.TRUE : body.getIsActive());
        if (Boolean.TRUE.equals(t.getIsActive())) assertNoOtherActiveTemplate(t.getProjectType(), null);
        t.setCreatedBy(userId);
        return headerMap(templateRepo.save(t));
    }

    @Transactional
    public Map<String, Object> updateTemplate(Long id, TemplateHeaderRequest body) throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Template not found"));
        // Check the state this edit would LEAVE the template in, so both
        // activating an inactive template and re-typing an active one are caught.
        String nextType = (body.getProjectType() != null && !body.getProjectType().isBlank())
                ? body.getProjectType().trim() : t.getProjectType();
        boolean nextActive = body.getIsActive() != null
                ? body.getIsActive() : Boolean.TRUE.equals(t.getIsActive());
        if (nextActive) assertNoOtherActiveTemplate(nextType, t.getId());

        if (body.getProjectType() != null && !body.getProjectType().isBlank()) {
            t.setProjectType(body.getProjectType().trim());
        }
        t.setName(body.getName());
        t.setDescription(body.getDescription());
        if (body.getIsActive() != null) t.setIsActive(body.getIsActive());
        return headerMap(templateRepo.save(t));
    }

    /** Two-step: active → deactivate; already inactive → soft-delete (with its lines). */
    @Transactional
    public void deleteTemplate(Long id) throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Template not found"));
        if (Boolean.TRUE.equals(t.getIsActive())) {
            t.setIsActive(false);
            templateRepo.save(t);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (LeadScopeTemplateItemEntity s : scopeItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(id)) {
            s.setDeletedAt(now); scopeItemRepo.save(s);
        }
        for (LeadBomTemplateItemEntity b : bomItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(id)) {
            b.setDeletedAt(now); bomItemRepo.save(b);
        }
        t.setDeletedAt(now);
        templateRepo.save(t);
    }

    // ── Scope lines (whole-list replace) ────────────────────────────────────────

    @Transactional
    public List<Map<String, Object>> saveScopeLines(Long templateId, TemplateScopeLinesRequest body, Long userId)
            throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new CustomException("Template not found"));
        List<TemplateScopeLineRequest> incoming = body.getItems() != null ? body.getItems() : List.of();
        for (TemplateScopeLineRequest r : incoming) {
            if (r.getActivity() == null || r.getActivity().isBlank()) {
                throw new CustomException("Every scope line needs an activity");
            }
        }
        // Rejects a bad total / a zero line and silently absorbs display rounding.
        // Enforced here as well as in the browser because this endpoint is
        // reachable without going through the Templates page.
        normaliseScopeWeights(incoming);

        List<LeadScopeTemplateItemEntity> existing =
                scopeItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(templateId);
        Map<Long, LeadScopeTemplateItemEntity> byId = new LinkedHashMap<>();
        for (LeadScopeTemplateItemEntity s : existing) byId.put(s.getId(), s);

        List<Long> kept = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 1;
        for (TemplateScopeLineRequest r : incoming) {
            LeadScopeTemplateItemEntity s = r.getId() != null ? byId.get(r.getId()) : null;
            if (s == null) {
                s = new LeadScopeTemplateItemEntity();
                s.setCreatedBy(userId);
            }
            s.setTemplateId(templateId);
            s.setProjectType(t.getProjectType());
            s.setSeqNo(seq++);
            s.setActivity(r.getActivity().trim());
            s.setCategory(r.getCategory());
            s.setSpecification(r.getSpecification());
            s.setUnit(r.getUnit());
            s.setWeightPct(r.getWeightPct());
            s.setWeightManual(Boolean.TRUE.equals(r.getWeightManual()));
            s.setNotes(r.getNotes());
            LeadScopeTemplateItemEntity saved = scopeItemRepo.save(s);
            kept.add(saved.getId());
            out.add(scopeLineMap(saved));
        }
        LocalDateTime now = LocalDateTime.now();
        for (LeadScopeTemplateItemEntity s : existing) {
            if (!kept.contains(s.getId())) { s.setDeletedAt(now); scopeItemRepo.save(s); }
        }
        // Bump the template version so projects generated after this edit pin the new
        // version; already-generated projects keep their materialised scope untouched.
        t.setVersion((t.getVersion() == null ? 1 : t.getVersion()) + 1);
        templateRepo.save(t);
        return out;
    }

    // ── BOM lines (whole-list replace) ──────────────────────────────────────────

    @Transactional
    public List<Map<String, Object>> saveBomLines(Long templateId, TemplateBomLinesRequest body, Long userId)
            throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new CustomException("Template not found"));
        List<TemplateBomLineRequest> incoming = body.getLines() != null ? body.getLines() : List.of();
        for (int i = 0; i < incoming.size(); i++) {
            TemplateBomLineRequest r = incoming.get(i);
            if (r.getItemName() == null || r.getItemName().isBlank()) {
                throw new CustomException("Every BOM line needs an item name");
            }
            if (r.getBasis() != null && !isValidBasis(r.getBasis())) {
                throw new CustomException("Invalid basis: " + r.getBasis());
            }
            // A line saved without the number its basis needs produces a blank
            // quantity on every lead seeded from this template, with nothing on
            // the lead pointing back here — so it is refused at the source.
            String problem = incompleteBasisReason(r);
            if (problem != null) {
                throw new CustomException("Line " + (i + 1) + " \"" + r.getItemName().trim() + "\": " + problem);
            }
        }

        List<LeadBomTemplateItemEntity> existing =
                bomItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(templateId);
        Map<Long, LeadBomTemplateItemEntity> byId = new LinkedHashMap<>();
        for (LeadBomTemplateItemEntity b : existing) byId.put(b.getId(), b);

        List<Long> kept = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 1;
        for (TemplateBomLineRequest r : incoming) {
            LeadBomTemplateItemEntity b = r.getId() != null ? byId.get(r.getId()) : null;
            if (b == null) {
                b = new LeadBomTemplateItemEntity();
                b.setCreatedBy(userId);
            }
            b.setTemplateId(templateId);
            b.setProjectType(t.getProjectType());
            b.setSeqNo(seq++);
            b.setScopeActivity(blankToNull(r.getScopeActivity()));
            b.setCategory(r.getCategory());
            b.setItemName(r.getItemName().trim());
            b.setMatchKey(normalizeKey(r.getItemName()));
            b.setBomItemId(r.getBomItemId());
            b.setMake(r.getMake());
            b.setSpecification(r.getSpecification());
            b.setUnit(r.getUnit());
            b.setBasis(r.getBasis() == null ? LeadBomTemplateItemEntity.BASIS_PER_KW : r.getBasis());
            b.setBasisValue(r.getBasisValue());
            b.setStepValue(r.getStepValue());
            b.setSiteVisitField(blankToNull(r.getSiteVisitField()));
            b.setDefaultUnitRate(r.getDefaultUnitRate());
            b.setNotes(r.getNotes());

            // Pick-a-make: when a catalog item + allowed variants are attached, the
            // default variant's make/spec become the line's snapshot — this is what
            // the Suggest engine reads (t.getMake()), so the engine stays untouched.
            List<Long> allowedIds = r.getBomItemId() != null && r.getAllowedVariantIds() != null
                    ? r.getAllowedVariantIds() : List.of();
            Long defaultVariantId = r.getDefaultVariantId();
            if (!allowedIds.isEmpty()) {
                if (defaultVariantId == null || !allowedIds.contains(defaultVariantId)) {
                    defaultVariantId = allowedIds.get(0); // sensible fallback default
                }
                BomItemVariantEntity dv = bomItemVariantRepo.findById(defaultVariantId).orElse(null);
                if (dv != null) {
                    b.setMake(variantMakeLabel(dv.getMake(), dv.getModel()));
                    if (dv.getDescription() != null && !dv.getDescription().isBlank()) {
                        b.setSpecification(dv.getDescription());
                    }
                }
            }

            LeadBomTemplateItemEntity saved = bomItemRepo.save(b);
            kept.add(saved.getId());

            // Replace this line's allowed-variant set (curated subset + default flag).
            templateLineVariantRepo.deleteByTemplateItemId(saved.getId());
            for (Long vid : allowedIds) {
                if (vid == null) continue;
                TemplateLineVariantEntity tlv = new TemplateLineVariantEntity();
                tlv.setTemplateItemId(saved.getId());
                tlv.setVariantId(vid);
                tlv.setIsDefault(vid.equals(defaultVariantId));
                templateLineVariantRepo.save(tlv);
            }
            out.add(bomLineMap(saved));
        }
        LocalDateTime now = LocalDateTime.now();
        for (LeadBomTemplateItemEntity b : existing) {
            if (!kept.contains(b.getId())) { b.setDeletedAt(now); bomItemRepo.save(b); }
        }
        // Bump the template version so projects generated after this edit pin the new
        // version; already-generated projects keep their materialised BOM untouched.
        t.setVersion((t.getVersion() == null ? 1 : t.getVersion()) + 1);
        templateRepo.save(t);
        return out;
    }

    // ── Weights ─────────────────────────────────────────────────────────────────

    /** Weights are stored to six decimals but shown to two. */
    private static final int WEIGHT_SCALE = 6;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    /** The most a single two-decimal display value can differ from what's stored. */
    private static final BigDecimal DISPLAY_ROUNDING_STEP = new BigDecimal("0.005");

    /**
     * Validates the incoming weights and normalises them in place, so the persist
     * loop below writes corrected values.
     *
     * <ul>
     *   <li>No weights at all → even split. This is a template that predates the
     *       feature (or a client that doesn't send weights); it is not an error.</li>
     *   <li>Any line at or below zero → rejected, naming the line. A 0% line never
     *       contributes to progress, so it is always a mistake.</li>
     *   <li>Total off by no more than two-decimal display rounding could explain →
     *       absorbed silently onto the largest line.</li>
     *   <li>Anything else → rejected, stating the actual total.</li>
     * </ul>
     */
    private void normaliseScopeWeights(List<TemplateScopeLineRequest> lines) throws CustomException {
        if (lines.isEmpty()) return;

        boolean allNull = true;
        for (TemplateScopeLineRequest r : lines) {
            if (r.getWeightPct() != null) { allNull = false; break; }
        }
        if (allNull) { evenSplit(lines); return; }

        BigDecimal sum = BigDecimal.ZERO;
        for (TemplateScopeLineRequest r : lines) {
            if (r.getWeightPct() != null) sum = sum.add(r.getWeightPct());
        }
        BigDecimal delta = ONE_HUNDRED.subtract(sum);

        // The total is checked before the per-line check: when both are wrong —
        // one line typed at 120% leaves the auto-balanced lines at 0% — naming the
        // total explains the problem, whereas naming a zeroed line points away
        // from what the user actually did.
        //
        // A user who retypes each value exactly as displayed enters a slightly
        // different number than what was stored — up to 0.005 per line. Absorb
        // that much; more than that is a genuine mistake and must not be swallowed.
        BigDecimal tolerance = DISPLAY_ROUNDING_STEP.multiply(BigDecimal.valueOf(lines.size()));
        if (delta.abs().compareTo(tolerance) > 0) {
            throw new CustomException("Scope weights total "
                    + sum.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + "% — they must add up to 100%.");
        }

        for (TemplateScopeLineRequest r : lines) {
            BigDecimal w = r.getWeightPct();
            if (w == null || w.signum() <= 0) {
                throw new CustomException("Scope line \"" + r.getActivity().trim()
                        + "\" has a weight of 0% — every line must carry a weight above zero.");
            }
        }

        if (delta.signum() == 0) return;

        // The correction goes to the largest line: proportionally the smallest
        // possible adjustment, and invisible at two decimals.
        TemplateScopeLineRequest largest = lines.get(0);
        for (TemplateScopeLineRequest r : lines) {
            if (r.getWeightPct().compareTo(largest.getWeightPct()) > 0) largest = r;
        }
        largest.setWeightPct(largest.getWeightPct().add(delta).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP));
    }

    /** Even split totalling exactly 100 — the last line absorbs the remainder. */
    private void evenSplit(List<TemplateScopeLineRequest> lines) {
        BigDecimal each = ONE_HUNDRED.divide(BigDecimal.valueOf(lines.size()), WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal w = (i == lines.size() - 1) ? ONE_HUNDRED.subtract(running) : each;
            lines.get(i).setWeightPct(w.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP));
            lines.get(i).setWeightManual(Boolean.FALSE);
            running = running.add(w);
        }
    }

    /**
     * Scope generation resolves THE active template for a project type, so a
     * second active one would silently combine two scopes into one project —
     * seeding it at 200% once weights carry meaning. The Templates page already
     * blocks this; enforced here too because the endpoint is reachable directly.
     */
    private void assertNoOtherActiveTemplate(String projectType, Long selfId) throws CustomException {
        LeadScopeTemplateEntity other = templateRepo
                .findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(projectType).orElse(null);
        if (other != null && !other.getId().equals(selfId)) {
            throw new CustomException("An active template already exists for " + projectType
                    + ". Deactivate it first.");
        }
    }

    // ── Basis completeness ──────────────────────────────────────────────────────

    /** Site-visit columns a FROM_SITE_VISIT line may read (mirrors latestSiteVisitFields). */
    private static final List<String> SITE_VISIT_FIELDS =
            List.of("ac_cable_length", "dc_cable_length", "sanctioned_load", "shadow_free_area");

    /** Issue codes shared with the Templates screen, so a saved line explains itself the same way. */
    static final String ISSUE_NEEDS_BASIS_VALUE = "NEEDS_BASIS_VALUE";
    static final String ISSUE_NEEDS_STEP_VALUE = "NEEDS_STEP_VALUE";
    static final String ISSUE_NEEDS_SITE_VISIT_FIELD = "NEEDS_SITE_VISIT_FIELD";
    static final String ISSUE_NEEDS_CATALOGUE_ITEM = "NEEDS_CATALOGUE_ITEM";
    static final String ISSUE_NEEDS_MAKE_ATTRIBUTE = "NEEDS_MAKE_ATTRIBUTE";
    static final String ISSUE_DEFAULT_MAKE_MISSING_ATTRIBUTE = "DEFAULT_MAKE_MISSING_ATTRIBUTE";

    /**
     * Why this incoming line could never produce a quantity, or null when it can.
     * Only conditions that are hopeless for EVERY lead block the save; a catalogue
     * that is merely patchy (some makes carry the attribute, some don't) is
     * reported by {@link #lineConfigIssue} and by the catalogue health check
     * instead, because the estimator can still pick a make that works.
     */
    private String incompleteBasisReason(TemplateBomLineRequest r) {
        String basis = r.getBasis() == null ? LeadBomTemplateItemEntity.BASIS_PER_KW : r.getBasis();
        switch (basis) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:
            case LeadBomTemplateItemEntity.BASIS_PER_KW:
            case LeadBomTemplateItemEntity.BASIS_PER_MODULE:
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER:
                if (isPositive(r.getBasisValue())) return null;
                // Demanding "a number above zero" and stopping there is how a module
                // line ends up as a fixed 2: the quickest way to satisfy the message
                // is to type something, and the basis that actually wanted no number
                // is never mentioned. So when the catalogue can drive one, say so here.
                return basisLabel(basis) + " needs a quantity value above zero — enter one in the Qty column."
                        + advicePostscript(r);
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:
                return isPositive(r.getStepValue()) ? null
                        : "Per step needs the kW per unit above zero — enter it in the Qty column.";
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT:
                if (r.getSiteVisitField() == null || r.getSiteVisitField().isBlank()) {
                    return "From site visit needs a site-visit field — pick one in the Qty column.";
                }
                return SITE_VISIT_FIELDS.contains(r.getSiteVisitField().trim()) ? null
                        : "\"" + r.getSiteVisitField().trim() + "\" is not a site-visit field the suggestion can read.";
            case LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK:
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW:
                return driverBasisReason(basis, r.getBomItemId(), r.getAllowedVariantIds());
            default:
                return null;
        }
    }

    /**
     * " Or switch it to …" — appended to a rejected line when the catalogue could
     * size it off the selected make instead, so the alternative is offered at the
     * exact moment the admin is deciding what number to type.
     */
    private String advicePostscript(TemplateBomLineRequest r) {
        Map<String, Object> adv = catalogueHealth.basisAdvice(
                r.getBomItemId(), r.getAllowedVariantIds(), r.getBasis());
        if (adv == null) return "";
        return " Or switch it to “" + adv.get("basisLabel") + "”, which reads the "
                + adv.get("attrLabel") + " off the selected make and needs no value here.";
    }

    /** The make-driven half of {@link #incompleteBasisReason}, shared with the saved-line check. */
    private String driverBasisReason(String basis, Long bomItemId, List<Long> allowedVariantIds) {
        String key = VariantAttributes.attrKeyForBasis(basis);
        String attr = attrLabel(bomItemId, key);
        if (bomItemId == null) {
            return basisLabel(basis) + " reads the " + attr + " off the selected make, so the line must be "
                    + "linked to a catalogue item — pick one in the Component column.";
        }
        List<BomItemVariantEntity> selectable = selectableMakes(bomItemId, allowedVariantIds);
        if (selectable.isEmpty()) {
            return "the linked catalogue item has no active makes, so no " + attr
                    + " can be read — add makes to it in Master Items.";
        }
        boolean any = selectable.stream()
                .anyMatch(v -> VariantAttributes.hasUsableNumeric(v.getAttributeValues(), key));
        return any ? null
                : "no make of the linked catalogue item records a " + attr
                        + " — add it to at least one make in Master Items.";
    }

    /** The makes a lead may choose for this line: the curated subset when set, else every active make. */
    private List<BomItemVariantEntity> selectableMakes(Long bomItemId, List<Long> allowedVariantIds) {
        List<BomItemVariantEntity> active =
                bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(bomItemId);
        if (allowedVariantIds == null || allowedVariantIds.isEmpty()) return active;
        List<BomItemVariantEntity> curated = new ArrayList<>();
        for (BomItemVariantEntity v : active) if (allowedVariantIds.contains(v.getId())) curated.add(v);
        // A curated set that no longer matches any active make is stale, not empty
        // intent — fall back to the item's makes so the check reflects reality.
        return curated.isEmpty() ? active : curated;
    }

    /**
     * Whether this template line can produce a quantity today — the same verdict
     * the Templates screen shows, exposed so the lead BOM tab can ask "would
     * re-running the suggestion actually fix my blank line?" without a second,
     * drifting copy of these rules.
     */
    public boolean canProduceQuantity(LeadBomTemplateItemEntity b) {
        List<Long> allowed = new ArrayList<>();
        Long def = null;
        if (b.getId() != null) {
            for (TemplateLineVariantEntity x : templateLineVariantRepo.findByTemplateItemId(b.getId())) {
                allowed.add(x.getVariantId());
                if (Boolean.TRUE.equals(x.getIsDefault())) def = x.getVariantId();
            }
        }
        Map<String, Object> issue = lineConfigIssue(b, allowed, def);
        // A WARNING line still sizes (the estimator can pick another make); only a
        // BLOCKING one is guaranteed to come out blank.
        return issue == null || !"BLOCKING".equals(issue.get("severity"));
    }

    /**
     * Why a SAVED line cannot produce a quantity, as {@code {code, message, severity}},
     * or null when it is sound. This is what lets the Templates screen list the
     * broken lines without the admin opening every row.
     */
    private Map<String, Object> lineConfigIssue(LeadBomTemplateItemEntity b, List<Long> allowed, Long defaultId) {
        String basis = b.getBasis() == null ? LeadBomTemplateItemEntity.BASIS_PER_KW : b.getBasis();
        switch (basis) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:
            case LeadBomTemplateItemEntity.BASIS_PER_KW:
            case LeadBomTemplateItemEntity.BASIS_PER_MODULE:
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER:
                if (!isPositive(b.getBasisValue())) {
                    return issue(ISSUE_NEEDS_BASIS_VALUE, "BLOCKING",
                            basisLabel(basis) + " has no quantity value — every lead gets a blank quantity for this line.");
                }
                return null;
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:
                if (!isPositive(b.getStepValue())) {
                    return issue(ISSUE_NEEDS_STEP_VALUE, "BLOCKING",
                            "Per step has no kW per unit — every lead gets a blank quantity for this line.");
                }
                return null;
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT:
                if (b.getSiteVisitField() == null || b.getSiteVisitField().isBlank()
                        || !SITE_VISIT_FIELDS.contains(b.getSiteVisitField().trim())) {
                    return issue(ISSUE_NEEDS_SITE_VISIT_FIELD, "BLOCKING",
                            "From site visit names no readable site-visit field.");
                }
                return null;
            case LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK:
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW:
                return driverLineIssue(basis, b.getBomItemId(), allowed, defaultId);
            default:
                return null;
        }
    }

    private Map<String, Object> driverLineIssue(String basis, Long bomItemId, List<Long> allowed, Long defaultId) {
        String key = VariantAttributes.attrKeyForBasis(basis);
        String attr = attrLabel(bomItemId, key);
        if (bomItemId == null) {
            return issue(ISSUE_NEEDS_CATALOGUE_ITEM, "BLOCKING",
                    basisLabel(basis) + " is not linked to a catalogue item, so no " + attr + " can be read.");
        }
        List<BomItemVariantEntity> selectable = selectableMakes(bomItemId, allowed);
        boolean any = selectable.stream()
                .anyMatch(v -> VariantAttributes.hasUsableNumeric(v.getAttributeValues(), key));
        if (!any) {
            return issue(ISSUE_NEEDS_MAKE_ATTRIBUTE, "BLOCKING",
                    "No make of this item records a " + attr + ", so the count cannot be computed.");
        }
        // The default is what a fresh suggestion picks, so a default without the
        // attribute means every new lead starts blank even though a fix exists.
        BomItemVariantEntity def = null;
        for (BomItemVariantEntity v : selectable) {
            if (defaultId != null && defaultId.equals(v.getId())) { def = v; break; }
        }
        if (def == null && !selectable.isEmpty()) def = selectable.get(0);
        if (def != null && !VariantAttributes.hasUsableNumeric(def.getAttributeValues(), key)) {
            return issue(ISSUE_DEFAULT_MAKE_MISSING_ATTRIBUTE, "WARNING",
                    "The default make (" + variantMakeLabel(def.getMake(), def.getModel()) + ") records no "
                            + attr + " — new leads start blank until another make is chosen.");
        }
        return null;
    }

    private static Map<String, Object> issue(String code, String severity, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("severity", severity);
        m.put("message", message);
        return m;
    }

    /** How the catalogue names this attribute, so messages match what the admin sees. */
    private String attrLabel(Long bomItemId, String key) {
        if (key == null) return "value";
        String schema = bomItemId == null ? null : bomItemsMasterRepo.findById(bomItemId)
                .map(BomItemsMasterEntity::getVariantAttributes).orElse(null);
        return VariantAttributes.labelForKey(schema, key);
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static String basisLabel(String basis) {
        switch (basis) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:          return "Fixed quantity";
            case LeadBomTemplateItemEntity.BASIS_PER_KW:         return "Per kW";
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:       return "Per step";
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT: return "From site visit";
            case LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK:  return "Module count (from Wp)";
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW: return "Inverter count (from kW)";
            case LeadBomTemplateItemEntity.BASIS_PER_MODULE:     return "Per module count";
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER:   return "Per inverter count";
            default: return basis;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private boolean isValidBasis(String basis) {
        return LeadBomTemplateItemEntity.BASIS_FIXED.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_KW.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_STEP.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_MODULE.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_INVERTER.equals(basis);
    }

    /** Same normalisation the engine uses to resolve mined lines, so keys line up. */
    private String normalizeKey(String itemName) {
        if (itemName == null) return "";
        return itemName.toLowerCase()
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Display label for a variant snapshot: "make model" (either part may be blank). */
    private static String variantMakeLabel(String make, String model) {
        StringBuilder sb = new StringBuilder();
        if (make != null && !make.isBlank()) sb.append(make.trim());
        if (model != null && !model.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(model.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private Map<String, Object> headerMap(LeadScopeTemplateEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("projectType", t.getProjectType());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("isActive", t.getIsActive());
        // Bumped on every line-list save; leads snapshot it so a BOM built from an
        // older version can say so.
        m.put("version", t.getVersion() == null ? 1 : t.getVersion());
        return m;
    }

    private Map<String, Object> scopeLineMap(LeadScopeTemplateItemEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("seqNo", s.getSeqNo());
        m.put("activity", s.getActivity());
        m.put("category", s.getCategory());
        m.put("specification", s.getSpecification());
        m.put("unit", s.getUnit());
        m.put("weightPct", s.getWeightPct());
        m.put("weightManual", Boolean.TRUE.equals(s.getWeightManual()));
        m.put("notes", s.getNotes());
        return m;
    }

    private Map<String, Object> bomLineMap(LeadBomTemplateItemEntity b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("seqNo", b.getSeqNo());
        m.put("scopeActivity", b.getScopeActivity());
        m.put("category", b.getCategory());
        m.put("itemName", b.getItemName());
        m.put("make", b.getMake());
        m.put("specification", b.getSpecification());
        m.put("unit", b.getUnit());
        m.put("basis", b.getBasis());
        m.put("basisValue", b.getBasisValue());
        m.put("stepValue", b.getStepValue());
        m.put("siteVisitField", b.getSiteVisitField());
        m.put("defaultUnitRate", b.getDefaultUnitRate());
        m.put("notes", b.getNotes());

        // Pick-a-make: catalog link + this line's curated allowed makes + default.
        m.put("bomItemId", b.getBomItemId());
        List<Long> allowed = new ArrayList<>();
        Long def = null;
        if (b.getId() != null) {
            for (TemplateLineVariantEntity x : templateLineVariantRepo.findByTemplateItemId(b.getId())) {
                allowed.add(x.getVariantId());
                if (Boolean.TRUE.equals(x.getIsDefault())) def = x.getVariantId();
            }
        }
        m.put("allowedVariantIds", allowed);
        m.put("defaultVariantId", def);
        // Why this line can't (or might not) produce a quantity — so the Templates
        // screen can list the broken lines without opening every row.
        m.put("configIssue", lineConfigIssue(b, allowed, def));
        // A better basis the catalogue could already drive. Kept SEPARATE from
        // configIssue: a fixed-quantity line is not at fault, so advice must not
        // colour the row or join the "lines need attention" count.
        m.put("basisAdvice", catalogueHealth.basisAdvice(b.getBomItemId(), allowed, b.getBasis()));
        return m;
    }
}
