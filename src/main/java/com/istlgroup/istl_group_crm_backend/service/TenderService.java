package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;

/**
 * Tender CRUD. Follows the OrderBook loose-FK recipe: save the parent, then
 * persist each child collection via its own repo (delete-and-reinsert on update,
 * mirroring the frontend's whole-object save). All numeric/date fields arrive as
 * String in the wrapper and are parsed here.
 */
@Service
public class TenderService {

    @Autowired private TenderRepo tenderRepo;
    @Autowired private TenderBoqItemRepo boqRepo;
    @Autowired private TenderEligibilityRepo eligibilityRepo;
    @Autowired private TenderDocumentRepo documentRepo;
    @Autowired private TenderDocRequestRepo docRequestRepo;
    @Autowired private TenderApprovalLogRepo approvalLogRepo;
    @Autowired private TenderPdfExtractor pdfExtractor;
    @Autowired private TenderPdfAiExtractor pdfAiExtractor;

    private static final Logger log = LoggerFactory.getLogger(TenderService.class);
    // Matches spring.servlet.multipart.max-file-size (50MB). A lower cap here just
    // rejects big tenders before Spring ever complains — and government NIT packs
    // routinely run to 150–200 pages.
    private static final long MAX_PDF_BYTES = 50L * 1024 * 1024;

    // ── reads ────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<TenderWrapper> getAll() {
        List<TenderWrapper> out = new ArrayList<>();
        for (TenderEntity t : tenderRepo.findByDeletedAtIsNullOrderByCreatedAtDesc()) {
            out.add(toWrapper(t, true));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public TenderWrapper getById(Long id) throws CustomException {
        TenderEntity t = tenderRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Tender not found"));
        return toWrapper(t, true);
    }

    // ── writes ───────────────────────────────────────────────────────────
    @Transactional
    public TenderWrapper create(TenderWrapper w, Long userId) throws CustomException {
        TenderEntity t = new TenderEntity();
        applyScalars(t, w);
        t.setCreatedBy(userId);
        TenderEntity saved = tenderRepo.save(t);
        saveChildren(saved.getId(), w);
        return toWrapper(tenderRepo.findById(saved.getId()).orElse(saved), true);
    }

    @Transactional
    public TenderWrapper update(Long id, TenderWrapper w, Long userId) throws CustomException {
        TenderEntity t = tenderRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Tender not found"));
        applyScalars(t, w);
        tenderRepo.save(t);
        // whole-object save: replace all child collections
        deleteChildren(id);
        saveChildren(id, w);
        return toWrapper(tenderRepo.findById(id).orElse(t), true);
    }

    @Transactional
    public void delete(Long id) throws CustomException {
        TenderEntity t = tenderRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Tender not found"));
        t.setDeletedAt(LocalDateTime.now());
        tenderRepo.save(t);
    }

    // ── source-PDF: parse (stateless), store (BLOB), fetch (for streaming) ──

    /** Stateless parse → partial field-map (keys match tenderData.js).
     *  LLM-primary so it reads arbitrary tender templates and normalises
     *  Lakh/Crore money to plain rupees; the regex extractor is the fallback
     *  used only when Groq is unavailable or returns nothing usable. */
    public Map<String, Object> parsePdf(MultipartFile file) throws CustomException {
        validatePdf(file);
        String text;
        try {
            text = TenderPdfExtractor.loadText(file.getBytes());
        } catch (IOException e) {
            throw new CustomException("Could not read the PDF: " + e.getMessage());
        }
        // A PDF with no text layer (a scan/photocopy) yields nothing for either
        // parser — say so plainly instead of reporting "no fields recognised".
        if (text.strip().length() < 200) {
            throw new CustomException(
                    "This PDF has no readable text layer (it looks like a scan), so fields "
                  + "can't be extracted automatically. Please enter them manually.");
        }

        // 1) Free path first — PDFBox text + deterministic regex. Costs no tokens,
        //    and on a template it recognises it is as good as the LLM.
        Map<String, Object> regex = pdfExtractor.extractFromText(text);
        if (isSufficient(regex)) {
            log.info("Tender parse: regex got {} field(s) — LLM not needed", regex.size());
            return tidyNames(regex);
        }

        // 2) Regex couldn't handle this template — only now spend tokens.
        log.info("Tender parse: regex got only {} field(s); calling the AI extractor", regex.size());
        String aiError;
        try {
            Map<String, Object> ai = pdfAiExtractor.extractFromText(text);
            if (ai != null && !ai.isEmpty()) {
                // Keep anything regex found that the AI missed; AI wins conflicts.
                Map<String, Object> merged = new LinkedHashMap<>(regex);
                merged.putAll(ai);
                return tidyNames(merged);
            }
            aiError = "the AI returned no recognisable fields";
            log.warn("AI tender parse returned nothing");
        } catch (Exception e) {
            aiError = e.getMessage();
            log.warn("AI tender parse failed: {}", e.getMessage());
        }

        // 3) Both paths came up short — a partial regex result still beats nothing.
        if (!regex.isEmpty()) return tidyNames(regex);
        throw new CustomException("Could not extract fields from this PDF — " + aiError + ".");
    }

    /**
     * Trim the work title however it was produced. The LLM in particular tends to
     * hand back the whole cover page — description, tender reference, agency,
     * address, phone, email, URL and the bidder's signature line as one string —
     * so the same tidy-up is applied to every path rather than trusting the prompt.
     */
    private Map<String, Object> tidyNames(Map<String, Object> fields) {
        Object name = fields.get("tenderName");
        if (name instanceof String) {
            String tidy = TenderPdfExtractor.tidyTenderName((String) name);
            if (tidy == null || tidy.isEmpty()) fields.remove("tenderName");
            else fields.put("tenderName", tidy);
        }
        return fields;
    }

    /**
     * Is a regex result good enough to skip the LLM?
     *
     * <p>Deliberately NOT "found at least one field": on an unfamiliar template the
     * regex still scrapes stray matches (it will pull "Karnataka" into {@code state}
     * from almost any Karnataka tender), and treating that as success would save
     * tokens by silently importing an almost-empty tender. So we require the core
     * identity of the tender plus at least one commercial/date detail — the shape
     * only a genuinely recognised template produces.
     */
    private boolean isSufficient(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return false;
        long core = CORE_FIELDS.stream().filter(m::containsKey).count();
        boolean hasDetail = DETAIL_FIELDS.stream().anyMatch(m::containsKey);
        return core >= 2 && hasDetail;
    }

    private static final Set<String> CORE_FIELDS =
            Set.of("tenderNumber", "tenderName", "issuingAuthority");
    private static final Set<String> DETAIL_FIELDS =
            Set.of("estimatedValue", "emdAmount", "submissionDeadline");

    /** Store the uploaded PDF bytes on the tender row (mirrors OrderBook PO). */
    @Transactional
    public TenderWrapper uploadSourcePdf(Long id, MultipartFile file) throws CustomException {
        validatePdf(file);
        TenderEntity t = tenderRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Tender not found"));
        try {
            String mime = (file.getContentType() != null && !file.getContentType().isBlank())
                    ? file.getContentType() : "application/pdf";
            t.setSourcePdfData(file.getBytes());
            t.setSourcePdfName(file.getOriginalFilename());
            t.setSourcePdfMimeType(mime);
            t.setSourcePdfSize(file.getSize());
            tenderRepo.save(t);
        } catch (IOException e) {
            throw new CustomException("Could not store the PDF: " + e.getMessage());
        }
        return toWrapper(t, false);
    }

    /** Fetch a tender that has stored PDF bytes, for the streaming endpoint. */
    @Transactional(readOnly = true)
    public TenderEntity getSourcePdfEntity(Long id) throws CustomException {
        TenderEntity t = tenderRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Tender not found"));
        if (t.getSourcePdfData() == null || t.getSourcePdfData().length == 0) {
            throw new CustomException("No PDF attached to this tender");
        }
        return t;
    }

    private void validatePdf(MultipartFile file) throws CustomException {
        if (file == null || file.isEmpty()) throw new CustomException("No file provided");
        if (file.getSize() > MAX_PDF_BYTES) throw new CustomException("File exceeds the 50 MB limit");
        String mime = file.getContentType();
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        boolean isPdf = (mime != null && mime.toLowerCase().contains("pdf")) || name.endsWith(".pdf");
        if (!isPdf) throw new CustomException("Only PDF files are supported");
    }

    // ── child persistence ─────────────────────────────────────────────────
    private void deleteChildren(Long tenderId) {
        boqRepo.deleteByTenderId(tenderId);
        eligibilityRepo.deleteByTenderId(tenderId);
        documentRepo.deleteByTenderId(tenderId);
        docRequestRepo.deleteByTenderId(tenderId);
        approvalLogRepo.deleteByTenderId(tenderId);
    }

    private void saveChildren(Long tenderId, TenderWrapper w) {
        if (w.getBoqItems() != null) {
            int i = 0;
            for (TenderBoqItemWrapper b : w.getBoqItems()) {
                TenderBoqItemEntity e = new TenderBoqItemEntity();
                e.setTenderId(tenderId);
                e.setSortNo(i++);
                e.setItemNo(b.getItemNo());
                e.setScope(b.getScope());
                e.setDescription(b.getDescription());
                e.setUnit(b.getUnit());
                e.setQuantity(bd(b.getQuantity()));
                e.setTenderRate(bd(b.getTenderRate()));
                e.setMaterialRate(bd(b.getMaterialRate()));
                e.setLabourRate(bd(b.getLabourRate()));
                boqRepo.save(e);
            }
        }
        if (w.getEligibilityCriteria() != null) {
            int i = 0;
            for (TenderEligibilityWrapper c : w.getEligibilityCriteria()) {
                TenderEligibilityEntity e = new TenderEligibilityEntity();
                e.setTenderId(tenderId);
                e.setSortNo(i++);
                e.setCategory(c.getCategory());
                e.setCriterionName(c.getCriterionName());
                e.setRequiredValue(c.getRequiredValue());
                e.setOurValue(c.getOurValue());
                e.setOperator(c.getOperator());
                e.setOverrideFlag(Boolean.TRUE.equals(c.getOverrideFlag()));
                e.setOverrideReason(c.getOverrideReason());
                e.setOverrideBy(c.getOverrideBy());
                e.setOverrideAt(dt(c.getOverrideAt()));
                eligibilityRepo.save(e);
            }
        }
        if (w.getDocuments() != null) {
            int i = 0;
            for (TenderDocumentWrapper d : w.getDocuments()) {
                TenderDocumentEntity e = new TenderDocumentEntity();
                e.setTenderId(tenderId);
                e.setSortNo(i++);
                e.setDocumentName(d.getDocumentName());
                e.setStatus(d.getStatus());
                e.setLink(d.getLink());
                e.setNotes(d.getNotes());
                documentRepo.save(e);
            }
        }
        if (w.getDocRequests() != null) {
            int i = 0;
            for (TenderDocRequestWrapper d : w.getDocRequests()) {
                TenderDocRequestEntity e = new TenderDocRequestEntity();
                e.setTenderId(tenderId);
                e.setSortNo(i++);
                e.setLabel(d.getLabel());
                e.setDepartment(d.getDepartment());
                e.setDueDate(dt(d.getDueDate()));
                e.setStatus(d.getStatus());
                e.setNotes(d.getNotes());
                docRequestRepo.save(e);
            }
        }
        if (w.getApprovalLog() != null) {
            for (TenderApprovalLogWrapper a : w.getApprovalLog()) {
                TenderApprovalLogEntity e = new TenderApprovalLogEntity();
                e.setTenderId(tenderId);
                e.setStage(a.getStage());
                e.setAction(a.getAction());
                e.setActionBy(a.getBy());
                e.setRemarks(a.getRemarks());
                e.setCreatedAt(dtm(a.getAt()));
                approvalLogRepo.save(e);
            }
        }
    }

    // ── mapping ────────────────────────────────────────────────────────────
    private void applyScalars(TenderEntity t, TenderWrapper w) {
        // Values scraped out of a PDF can be far longer than the column allows
        // (NIT titles run to several hundred characters). Clip to the mapped
        // length so an over-long field is saved shortened rather than failing the
        // whole insert with "Data too long for column".
        t.setTenderNumber(clip(w.getTenderNumber(), 255));
        t.setTenderName(clip(w.getTenderName(), 1000));
        t.setIssuingAuthority(clip(w.getIssuingAuthority(), 500));
        t.setClientCompany(clip(w.getClientCompany(), 500));
        t.setClientType(clip(w.getClientType(), 255));
        t.setClientGstin(clip(w.getClientGstin(), 255));
        t.setClientPan(clip(w.getClientPan(), 255));
        t.setClientCin(clip(w.getClientCin(), 255));
        t.setClientContactPerson(clip(w.getClientContactPerson(), 255));
        t.setClientContactEmail(clip(w.getClientContactEmail(), 255));
        t.setClientContactPhone(clip(w.getClientContactPhone(), 255));
        t.setClientAddress(w.getClientAddress());   // TEXT
        t.setClientCity(clip(w.getClientCity(), 255));
        t.setClientState(clip(w.getClientState(), 255));
        t.setSector(clip(w.getSector(), 255));
        t.setTenderType(clip(w.getTenderType(), 255));
        t.setSource(clip(w.getSource(), 255));
        t.setPortalLink(w.getPortalLink());         // TEXT
        t.setLocation(clip(w.getLocation(), 500));
        t.setDistrict(clip(w.getDistrict(), 255));
        t.setState(clip(w.getState(), 255));
        t.setFinancialYear(clip(w.getFinancialYear(), 255));
        t.setEstimatedValue(bd(w.getEstimatedValue()));
        t.setEmdAmount(bd(w.getEmdAmount()));
        t.setPerformanceSecurityPct(bd(w.getPerformanceSecurityPct()));
        t.setSubmissionDeadline(dt(w.getSubmissionDeadline()));
        t.setTechnicalOpeningDate(dt(w.getTechnicalOpeningDate()));
        t.setFinancialOpeningDate(dt(w.getFinancialOpeningDate()));
        if (w.getEligibilityDecision() != null) t.setEligibilityDecision(w.getEligibilityDecision());
        if (w.getOverheadPct() != null) t.setOverheadPct(bd(w.getOverheadPct()));
        if (w.getProfitPct() != null) t.setProfitPct(bd(w.getProfitPct()));
        t.setGoNoGo(w.getGoNoGo());
        t.setGoNoGoReason(w.getGoNoGoReason());
        t.setGoNoGoDate(dt(w.getGoNoGoDate()));
        if (w.getCfoApprovalStatus() != null) t.setCfoApprovalStatus(w.getCfoApprovalStatus());
        t.setCfoApprovalRemarks(w.getCfoApprovalRemarks());
        t.setCfoApprovalDate(dt(w.getCfoApprovalDate()));
        if (w.getMdApprovalStatus() != null) t.setMdApprovalStatus(w.getMdApprovalStatus());
        t.setMdApprovalRemarks(w.getMdApprovalRemarks());
        t.setMdApprovalDate(dt(w.getMdApprovalDate()));
        t.setSubmissionMode(w.getSubmissionMode());
        t.setSubmissionReference(w.getSubmissionReference());
        t.setSubmissionDate(dt(w.getSubmissionDate()));
        t.setSubmittedBy(w.getSubmittedBy());
        t.setL1Value(bd(w.getL1Value()));
        t.setOurRank(w.getOurRank());
        if (w.getStatus() != null && !w.getStatus().trim().isEmpty()) t.setStatus(w.getStatus());
        t.setLossReason(w.getLossReason());
        t.setResult(w.getResult());
        t.setCompetitorNotes(w.getCompetitorNotes());
        t.setContractValue(bd(w.getContractValue()));
        t.setLoaNumber(w.getLoaNumber());
        t.setLoaDate(dt(w.getLoaDate()));
        t.setAgreementDate(dt(w.getAgreementDate()));
        t.setProjectId(w.getProjectId());
    }

    private TenderWrapper toWrapper(TenderEntity t, boolean includeChildren) {
        TenderWrapper w = new TenderWrapper();
        w.setId(t.getId());
        w.setTenderNumber(t.getTenderNumber());
        w.setTenderName(t.getTenderName());
        w.setIssuingAuthority(t.getIssuingAuthority());
        w.setClientCompany(t.getClientCompany());
        w.setClientType(t.getClientType());
        w.setClientGstin(t.getClientGstin());
        w.setClientPan(t.getClientPan());
        w.setClientCin(t.getClientCin());
        w.setClientContactPerson(t.getClientContactPerson());
        w.setClientContactEmail(t.getClientContactEmail());
        w.setClientContactPhone(t.getClientContactPhone());
        w.setClientAddress(t.getClientAddress());
        w.setClientCity(t.getClientCity());
        w.setClientState(t.getClientState());
        w.setSector(t.getSector());
        w.setTenderType(t.getTenderType());
        w.setSource(t.getSource());
        w.setPortalLink(t.getPortalLink());
        w.setLocation(t.getLocation());
        w.setDistrict(t.getDistrict());
        w.setState(t.getState());
        w.setFinancialYear(t.getFinancialYear());
        w.setEstimatedValue(s(t.getEstimatedValue()));
        w.setEmdAmount(s(t.getEmdAmount()));
        w.setPerformanceSecurityPct(s(t.getPerformanceSecurityPct()));
        w.setSubmissionDeadline(s(t.getSubmissionDeadline()));
        w.setTechnicalOpeningDate(s(t.getTechnicalOpeningDate()));
        w.setFinancialOpeningDate(s(t.getFinancialOpeningDate()));
        w.setEligibilityDecision(t.getEligibilityDecision());
        w.setOverheadPct(s(t.getOverheadPct()));
        w.setProfitPct(s(t.getProfitPct()));
        w.setGoNoGo(t.getGoNoGo());
        w.setGoNoGoReason(t.getGoNoGoReason());
        w.setGoNoGoDate(s(t.getGoNoGoDate()));
        w.setCfoApprovalStatus(t.getCfoApprovalStatus());
        w.setCfoApprovalRemarks(t.getCfoApprovalRemarks());
        w.setCfoApprovalDate(s(t.getCfoApprovalDate()));
        w.setMdApprovalStatus(t.getMdApprovalStatus());
        w.setMdApprovalRemarks(t.getMdApprovalRemarks());
        w.setMdApprovalDate(s(t.getMdApprovalDate()));
        w.setSubmissionMode(t.getSubmissionMode());
        w.setSubmissionReference(t.getSubmissionReference());
        w.setSubmissionDate(s(t.getSubmissionDate()));
        w.setSubmittedBy(t.getSubmittedBy());
        w.setL1Value(s(t.getL1Value()));
        w.setOurRank(t.getOurRank());
        w.setStatus(t.getStatus());
        w.setLossReason(t.getLossReason());
        w.setResult(t.getResult());
        w.setCompetitorNotes(t.getCompetitorNotes());
        w.setContractValue(s(t.getContractValue()));
        w.setLoaNumber(t.getLoaNumber());
        w.setLoaDate(s(t.getLoaDate()));
        w.setAgreementDate(s(t.getAgreementDate()));
        w.setProjectId(t.getProjectId());
        w.setCreatedAt(s(t.getCreatedAt()));
        w.setSourcePdfName(t.getSourcePdfName());
        w.setSourcePdfMimeType(t.getSourcePdfMimeType());
        w.setHasSourcePdf(t.getSourcePdfData() != null && t.getSourcePdfData().length > 0);

        if (includeChildren) {
            Long id = t.getId();

            List<TenderBoqItemWrapper> boq = new ArrayList<>();
            for (TenderBoqItemEntity e : boqRepo.findByTenderIdOrderBySortNo(id)) {
                TenderBoqItemWrapper b = new TenderBoqItemWrapper();
                b.setId(e.getId());
                b.setItemNo(e.getItemNo());
                b.setScope(e.getScope());
                b.setDescription(e.getDescription());
                b.setUnit(e.getUnit());
                b.setQuantity(s(e.getQuantity()));
                b.setTenderRate(s(e.getTenderRate()));
                b.setMaterialRate(s(e.getMaterialRate()));
                b.setLabourRate(s(e.getLabourRate()));
                boq.add(b);
            }
            w.setBoqItems(boq);

            List<TenderEligibilityWrapper> elig = new ArrayList<>();
            for (TenderEligibilityEntity e : eligibilityRepo.findByTenderIdOrderBySortNo(id)) {
                TenderEligibilityWrapper c = new TenderEligibilityWrapper();
                c.setId(e.getId());
                c.setCategory(e.getCategory());
                c.setCriterionName(e.getCriterionName());
                c.setRequiredValue(e.getRequiredValue());
                c.setOurValue(e.getOurValue());
                c.setOperator(e.getOperator());
                c.setOverrideFlag(e.getOverrideFlag());
                c.setOverrideReason(e.getOverrideReason());
                c.setOverrideBy(e.getOverrideBy());
                c.setOverrideAt(s(e.getOverrideAt()));
                elig.add(c);
            }
            w.setEligibilityCriteria(elig);

            List<TenderDocumentWrapper> docs = new ArrayList<>();
            for (TenderDocumentEntity e : documentRepo.findByTenderIdOrderBySortNo(id)) {
                TenderDocumentWrapper d = new TenderDocumentWrapper();
                d.setId(e.getId());
                d.setDocumentName(e.getDocumentName());
                d.setStatus(e.getStatus());
                d.setLink(e.getLink());
                d.setNotes(e.getNotes());
                docs.add(d);
            }
            w.setDocuments(docs);

            List<TenderDocRequestWrapper> reqs = new ArrayList<>();
            for (TenderDocRequestEntity e : docRequestRepo.findByTenderIdOrderBySortNo(id)) {
                TenderDocRequestWrapper d = new TenderDocRequestWrapper();
                d.setId(e.getId());
                d.setLabel(e.getLabel());
                d.setDepartment(e.getDepartment());
                d.setDueDate(s(e.getDueDate()));
                d.setStatus(e.getStatus());
                d.setNotes(e.getNotes());
                reqs.add(d);
            }
            w.setDocRequests(reqs);

            List<TenderApprovalLogWrapper> log = new ArrayList<>();
            for (TenderApprovalLogEntity e : approvalLogRepo.findByTenderIdOrderByCreatedAtDesc(id)) {
                TenderApprovalLogWrapper a = new TenderApprovalLogWrapper();
                a.setId(e.getId());
                a.setStage(e.getStage());
                a.setAction(e.getAction());
                a.setBy(e.getActionBy());
                a.setRemarks(e.getRemarks());
                a.setAt(s(e.getCreatedAt()));
                log.add(a);
            }
            w.setApprovalLog(log);
        }
        return w;
    }

    // ── parse / format helpers ─────────────────────────────────────────────
    private BigDecimal bd(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        try { return new BigDecimal(t); } catch (Exception e) { return null; }
    }

    private LocalDate dt(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        try { return LocalDate.parse(t); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(t).toLocalDate(); } catch (Exception ignored) { }
        return null;
    }

    private LocalDateTime dtm(String v) {
        if (v == null || v.trim().isEmpty()) return LocalDateTime.now();
        String t = v.trim();
        try { return OffsetDateTime.parse(t).toLocalDateTime(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(t); } catch (Exception ignored) { }
        try { return Instant.parse(t).atZone(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignored) { }
        return LocalDateTime.now();
    }

    /** Trim a value to the mapped column length (null-safe, whitespace-trimmed). */
    private String clip(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max).trim();
    }

    private String s(BigDecimal b) { return b == null ? null : b.toPlainString(); }
    private String s(LocalDate d) { return d == null ? null : d.toString(); }
    private String s(LocalDateTime d) { return d == null ? null : d.toString(); }
}
