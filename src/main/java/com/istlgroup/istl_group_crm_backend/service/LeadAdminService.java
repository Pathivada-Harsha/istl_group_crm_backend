package com.istlgroup.istl_group_crm_backend.service;

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
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.TemplateLineVariantEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.repo.TemplateLineVariantRepo;
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
    private final TemplateLineVariantRepo templateLineVariantRepo;

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
        t.setCreatedBy(userId);
        return headerMap(templateRepo.save(t));
    }

    @Transactional
    public Map<String, Object> updateTemplate(Long id, TemplateHeaderRequest body) throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Template not found"));
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
            s.setNotes(r.getNotes());
            LeadScopeTemplateItemEntity saved = scopeItemRepo.save(s);
            kept.add(saved.getId());
            out.add(scopeLineMap(saved));
        }
        LocalDateTime now = LocalDateTime.now();
        for (LeadScopeTemplateItemEntity s : existing) {
            if (!kept.contains(s.getId())) { s.setDeletedAt(now); scopeItemRepo.save(s); }
        }
        return out;
    }

    // ── BOM lines (whole-list replace) ──────────────────────────────────────────

    @Transactional
    public List<Map<String, Object>> saveBomLines(Long templateId, TemplateBomLinesRequest body, Long userId)
            throws CustomException {
        LeadScopeTemplateEntity t = templateRepo.findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new CustomException("Template not found"));
        List<TemplateBomLineRequest> incoming = body.getLines() != null ? body.getLines() : List.of();
        for (TemplateBomLineRequest r : incoming) {
            if (r.getItemName() == null || r.getItemName().isBlank()) {
                throw new CustomException("Every BOM line needs an item name");
            }
            if (r.getBasis() != null && !isValidBasis(r.getBasis())) {
                throw new CustomException("Invalid basis: " + r.getBasis());
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
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private boolean isValidBasis(String basis) {
        return LeadBomTemplateItemEntity.BASIS_FIXED.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_KW.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_PER_STEP.equals(basis)
                || LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT.equals(basis);
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
        return m;
    }
}
