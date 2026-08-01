package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerEntity;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerRepo;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerSanctionRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerWrapper;

/**
 * Borrower Registry service.
 *
 * <p>The import is stateless by design, mirroring {@code TenderService.parsePdf}:
 * {@link #parseSanction} reads a file and returns a field map without touching
 * the database. Nothing is written until the user has seen the review screen and
 * posted the values back through {@link #saveSanction}, because an extractor
 * will occasionally misread a figure and silently persisting that is worse than
 * not importing at all.
 */
@Service
public class BorrowerService {

    private static final Logger log = LoggerFactory.getLogger(BorrowerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    /** The seven identity fields the registry counts for completeness. */
    private static final int IDENTITY_TOTAL = 7;

    @Autowired private BorrowerRepo borrowerRepo;
    @Autowired private BorrowerSanctionRepo sanctionRepo;
    @Autowired private SanctionDocExtractor docExtractor;
    @Autowired private SanctionDocAiExtractor aiExtractor;
    @Autowired private SanctionDerivedCalculator derived;
    @Autowired private SanctionDocHtmlRenderer htmlRenderer;

    @PersistenceContext private EntityManager em;

    // ════════════════════════════════════════════════════════════════════════
    // Import — stateless parse
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Read a sanction letter and return a partial field map. Writes nothing.
     *
     * <p>Two tiers: the deterministic extractor first, because on a table-driven
     * letter it is as good as the LLM and costs no tokens; Groq only when that
     * comes back short.
     */
    public Map<String, Object> parseSanction(MultipartFile file) throws CustomException {
        validateFile(file);

        String name = file.getOriginalFilename();
        String ct   = file.getContentType();
        boolean docx = SanctionDocExtractor.isDocx(name, ct);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new CustomException("Could not read the uploaded file: " + e.getMessage());
        }

        Map<String, Object> deterministic;
        String text;
        try {
            text = SanctionDocExtractor.loadText(bytes, docx);
            deterministic = docx
                    ? docExtractor.extractDocx(bytes)
                    : docExtractor.extractFromPdfText(text);
        } catch (Exception e) {
            throw new CustomException("Could not read the document: " + e.getMessage());
        }

        // A PDF with no text layer yields nothing for either tier. Say so plainly
        // rather than reporting "no fields recognised".
        if (!docx && text.strip().length() < 200) {
            throw new CustomException(
                    "This PDF has no readable text layer (it looks like a scan), so fields "
                  + "can't be extracted automatically. Please enter them manually.");
        }

        String engine = docx ? "TABLE" : "REGEX";

        if (docExtractor.isSufficient(deterministic)) {
            log.info("Sanction parse: {} tier got {} field(s) — LLM not needed",
                     engine, deterministic.size());
            return withMeta(deterministic, engine, List.of());
        }

        log.info("Sanction parse: {} tier got only {} field(s); calling the AI extractor",
                 engine, deterministic.size());
        try {
            Map<String, Object> ai = aiExtractor.extractFromText(text);
            if (ai != null && !ai.isEmpty()) {
                // Keep what the deterministic pass found; it is the more
                // trustworthy of the two, so it wins conflicts.
                List<String> aiOnly = new ArrayList<>();
                Map<String, Object> merged = new LinkedHashMap<>(ai);
                merged.putAll(deterministic);
                for (String k : ai.keySet()) {
                    if (!deterministic.containsKey(k)) aiOnly.add(k);
                }
                return withMeta(merged, deterministic.isEmpty() ? "AI" : "MIXED", aiOnly);
            }
        } catch (Exception e) {
            log.warn("AI sanction parse failed: {}", e.getMessage());
        }

        if (deterministic.isEmpty()) {
            throw new CustomException(
                    "No fields could be read from this document. Please enter the details manually.");
        }
        return withMeta(deterministic, engine, List.of());
    }

    /**
     * Attach parse metadata the review screen uses: which engine ran, and which
     * fields came from the LLM so the user's eye goes to those first.
     */
    private Map<String, Object> withMeta(Map<String, Object> fields,
                                         String engine, List<String> lowConfidence) {
        Map<String, Object> out = new LinkedHashMap<>(fields);
        out.put("_extractionEngine", engine);
        out.put("_lowConfidenceFields", lowConfidence);
        // Flag a ref no. already on file so the review screen can warn before save.
        Object ref = fields.get("refNo");
        if (ref != null) {
            boolean dup = sanctionRepo
                    .findByRefNoIgnoreCaseAndDeletedAtIsNull(String.valueOf(ref))
                    .isPresent();
            out.put("_duplicateRefNo", dup);
        }
        return out;
    }

    private void validateFile(MultipartFile file) throws CustomException {
        if (file == null || file.isEmpty()) {
            throw new CustomException("No file uploaded");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new CustomException("File too large (max 15 MB)");
        }
        String name = file.getOriginalFilename();
        String ct   = file.getContentType();
        if (!SanctionDocExtractor.isDocx(name, ct) && !SanctionDocExtractor.isPdf(name, ct)) {
            throw new CustomException("Unsupported file type. Upload a PDF or a Word (.docx) file.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Borrowers
    // ════════════════════════════════════════════════════════════════════════

    public List<BorrowerWrapper> getAll(String search, String category) {
        // One query handles all four states; blank means "don't filter".
        String q = SanctionValueParser.isBlank(search) ? null : search.trim();
        String c = SanctionValueParser.isBlank(category) ? null : category.trim();
        List<BorrowerEntity> rows = borrowerRepo.search(q, c);

        List<BorrowerWrapper> out = new ArrayList<>();
        for (BorrowerEntity b : rows) {
            BorrowerWrapper w = toWrapper(b);
            List<BorrowerSanctionEntity> sanctions =
                    sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(b.getId());
            if (!sanctions.isEmpty()) {
                BorrowerSanctionEntity latest = sanctions.get(0);
                BorrowerSanctionWrapper lw = toWrapper(latest);
                w.getSanctions().add(lw);
                w.setLatestRefNo(latest.getRefNo());
                w.setLatestSanctionedAmount(SanctionValueParser.formatCrore(latest.getSanctionedAmount()));
                w.setLatestCategory(latest.getCategory());
                w.setLatestScheduledCod(SanctionValueParser.formatDate(latest.getScheduledCod()));
                w.setLatestCodStatus(lw.getDerivedCodStatus());
            }
            out.add(w);
        }
        return out;
    }

    public BorrowerWrapper getById(Long id) throws CustomException {
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        BorrowerWrapper w = toWrapper(b);
        for (BorrowerSanctionEntity s :
                sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(id)) {
            w.getSanctions().add(toWrapper(s));
        }
        return w;
    }

    @Transactional
    public BorrowerWrapper createBorrower(BorrowerWrapper in, Long userId) throws CustomException {
        if (SanctionValueParser.isBlank(in.getBorrowerName())) {
            throw new CustomException("Borrower name is required");
        }
        assertCinFree(in.getCin(), null);

        BorrowerEntity b = new BorrowerEntity();
        applyBorrower(b, in);
        b.setCreatedBy(userId);
        return toWrapper(borrowerRepo.save(b));
    }

    @Transactional
    public BorrowerWrapper updateBorrower(Long id, BorrowerWrapper in, Long userId)
            throws CustomException {
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        if (SanctionValueParser.isBlank(in.getBorrowerName())) {
            throw new CustomException("Borrower name is required");
        }
        assertCinFree(in.getCin(), id);

        applyBorrower(b, in);
        b.setUpdatedBy(userId);
        return getById(borrowerRepo.save(b).getId());
    }

    /**
     * Hard-deletes a borrower and every row that belongs to it, across all
     * registry tables. This is permanent and irreversible — there is no
     * {@code deleted_at} row left behind, so the reference numbers become free
     * for re-import immediately.
     *
     * <p>Children are removed before the parent so foreign keys never block the
     * delete. Every statement is scoped by {@code borrower_id}, so shared tables
     * such as {@code data_room_documents} only lose this borrower's rows.
     */
    @Transactional
    public void deleteBorrower(Long id, Long userId) throws CustomException {
        // findById (not ...AndDeletedAtIsNull) so an already soft-deleted
        // borrower can still be purged for good.
        borrowerRepo.findById(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));

        // Only borrower_sanctions still hangs off a borrower in the current
        // schema; the KYC / onboarding / snapshot / loan tables were removed.
        deleteByBorrowerId("borrower_sanctions", id);

        em.createNativeQuery("DELETE FROM borrowers WHERE id = :id")
          .setParameter("id", id)
          .executeUpdate();
    }

    /** Deletes all rows of {@code table} whose {@code borrower_id} matches. */
    private void deleteByBorrowerId(String table, Long borrowerId) {
        // 'table' is a fixed internal constant, never user input — no injection risk.
        em.createNativeQuery("DELETE FROM " + table + " WHERE borrower_id = :id")
          .setParameter("id", borrowerId)
          .executeUpdate();
    }

    private void assertCinFree(String cin, Long selfId) throws CustomException {
        if (SanctionValueParser.isBlank(cin)) return;
        Optional<BorrowerEntity> other =
                borrowerRepo.findByCinIgnoreCaseAndDeletedAtIsNull(cin.trim());
        if (other.isPresent() && !other.get().getId().equals(selfId)) {
            throw new CustomException(
                    "That CIN already belongs to " + other.get().getBorrowerName());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sanctions
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Save a reviewed sanction. Creates the borrower when the name is new, or
     * attaches the letter to the existing borrower when it isn't — a company
     * takes more than one facility over its life.
     */
    @Transactional
    public BorrowerWrapper saveSanction(BorrowerSanctionWrapper in, Long userId,
                                        String rawExtractedJson) throws CustomException {

        if (SanctionValueParser.isBlank(in.getRefNo())) {
            throw new CustomException("Reference number is required");
        }

        Optional<BorrowerSanctionEntity> dup =
                sanctionRepo.findByRefNoIgnoreCaseAndDeletedAtIsNull(in.getRefNo().trim());
        if (dup.isPresent() && !dup.get().getId().equals(in.getId())) {
            throw new CustomException(
                    "Sanction letter " + in.getRefNo() + " has already been imported.");
        }

        Long borrowerId = in.getBorrowerId();
        if (borrowerId == null) {
            throw new CustomException("A borrower must be selected or created first");
        }
        BorrowerEntity borrower = borrowerRepo.findByIdAndDeletedAtIsNull(borrowerId)
                .orElseThrow(() -> new CustomException("Borrower not found"));

        BorrowerSanctionEntity e = in.getId() == null
                ? new BorrowerSanctionEntity()
                : sanctionRepo.findByIdAndDeletedAtIsNull(in.getId())
                        .orElseThrow(() -> new CustomException("Sanction not found"));

        boolean isNew = e.getId() == null;
        applySanction(e, in);
        e.setBorrowerId(borrower.getId());

        if (isNew) {
            e.setCreatedBy(userId);
            if (rawExtractedJson != null) {
                e.setRawExtractedJson(rawExtractedJson);
            }
        } else {
            e.setUpdatedBy(userId);
            // A user has touched imported values; record that for the audit trail.
            if ("IMPORTED".equals(e.getSource())) {
                e.setSource("IMPORTED_EDITED");
            }
        }

        validateSanction(e);
        sanctionRepo.save(e);
        return getById(borrower.getId());
    }

    /**
     * Find an existing borrower by name, or create one. Called by the review
     * screen once the user has confirmed which company the letter belongs to.
     *
     * <p>The letter also carries borrower-level values the sanction row has no
     * home for — promoter, guarantor, group, Cat / Sub Cat. Those are filled in
     * here, but only where the borrower is still blank: an import must never
     * overwrite something a user typed.
     */
    @Transactional
    public BorrowerWrapper resolveBorrower(BorrowerWrapper in, Long userId) throws CustomException {
        if (in == null || SanctionValueParser.isBlank(in.getBorrowerName())) {
            throw new CustomException("Borrower name is required");
        }
        String name = in.getBorrowerName().trim();
        Optional<BorrowerEntity> existing =
                borrowerRepo.findByBorrowerNameIgnoreCaseAndDeletedAtIsNull(name);

        BorrowerEntity b;
        if (existing.isPresent()) {
            b = existing.get();
            if (!fillBlanks(b, in)) return getById(b.getId());
            b.setUpdatedBy(userId);
        } else {
            b = new BorrowerEntity();
            b.setBorrowerName(name);
            fillBlanks(b, in);
            b.setCreatedBy(userId);
        }
        return getById(borrowerRepo.save(b).getId());
    }

    /** Copy the parsed borrower-level values onto blank fields only. */
    private boolean fillBlanks(BorrowerEntity b, BorrowerWrapper in) {
        boolean changed = false;
        if (SanctionValueParser.isBlank(b.getSponsorName())
                && !SanctionValueParser.isBlank(in.getSponsorName())) {
            b.setSponsorName(SanctionValueParser.clean(in.getSponsorName()));
            changed = true;
        }
        if (SanctionValueParser.isBlank(b.getPromoterName())
                && !SanctionValueParser.isBlank(in.getPromoterName())) {
            b.setPromoterName(SanctionValueParser.clean(in.getPromoterName()));
            changed = true;
        }
        if (SanctionValueParser.isBlank(b.getGuarantorName())
                && !SanctionValueParser.isBlank(in.getGuarantorName())) {
            b.setGuarantorName(SanctionValueParser.clean(in.getGuarantorName()));
            changed = true;
        }
        if (SanctionValueParser.isBlank(b.getGroupName())
                && !SanctionValueParser.isBlank(in.getGroupName())) {
            b.setGroupName(SanctionValueParser.clean(in.getGroupName()));
            changed = true;
        }
        if (SanctionValueParser.isBlank(b.getBorrowerCategory())
                && !SanctionValueParser.isBlank(in.getBorrowerCategory())) {
            b.setBorrowerCategory(SanctionValueParser.clean(in.getBorrowerCategory()));
            changed = true;
        }
        if (SanctionValueParser.isBlank(b.getBorrowerSubCategory())
                && !SanctionValueParser.isBlank(in.getBorrowerSubCategory())) {
            b.setBorrowerSubCategory(SanctionValueParser.clean(in.getBorrowerSubCategory()));
            changed = true;
        }
        if (SanctionValueParser.isBlank(b.getState())
                && !SanctionValueParser.isBlank(in.getState())) {
            b.setState(SanctionValueParser.clean(in.getState()));
            changed = true;
        }
        return changed;
    }

    @Transactional
    public void deleteSanction(Long id, Long userId) throws CustomException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        s.setDeletedAt(LocalDateTime.now());
        s.setUpdatedBy(userId);
        sanctionRepo.save(s);
    }

    /** Blocking checks only. Soft mismatches surface as derived text, not errors. */
    private void validateSanction(BorrowerSanctionEntity e) throws CustomException {
        if (e.getProjectCost() != null && e.getSanctionedAmount() != null
                && e.getSanctionedAmount().compareTo(e.getProjectCost()) > 0) {
            throw new CustomException(
                    "The sanctioned amount cannot be more than the total project cost.");
        }
        if (e.getProjectCost() != null && e.getDebtAmount() != null
                && e.getDebtAmount().compareTo(e.getProjectCost()) > 0) {
            throw new CustomException("The debt cannot be more than the total project cost.");
        }
        if (e.getSanctionDate() != null && e.getScheduledCod() != null
                && e.getScheduledCod().isBefore(e.getSanctionDate())) {
            throw new CustomException("The scheduled COD cannot fall before the sanction date.");
        }
        if (e.getRepaymentStartDate() != null && e.getRepaymentEndDate() != null
                && e.getRepaymentEndDate().isBefore(e.getRepaymentStartDate())) {
            throw new CustomException(
                    "The repayment end date cannot fall before the repayment start date.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // The letter itself
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public BorrowerSanctionWrapper uploadDocument(Long sanctionId, MultipartFile file, Long userId)
            throws CustomException {
        validateFile(file);
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        try {
            s.setSanctionDocData(file.getBytes());
        } catch (Exception e) {
            throw new CustomException("Could not store the file: " + e.getMessage());
        }
        s.setSanctionDocName(file.getOriginalFilename());
        s.setSanctionDocMime(file.getContentType());
        s.setSanctionDocSize(file.getSize());
        s.setUpdatedBy(userId);
        return toWrapper(sanctionRepo.save(s));
    }

    /**
     * Build the payload the in-page viewer needs.
     *
     * <p>Runs inside a transaction and pulls the bytes by explicit query rather
     * than off a detached entity, which is what made the first version fail.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildPreview(Long sanctionId) throws CustomException, IOException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));

        byte[] data = sanctionRepo.findDocData(sanctionId);
        if (data == null || data.length == 0) {
            throw new CustomException("No document stored for this sanction");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", s.getSanctionDocName());
        payload.put("mimeType", s.getSanctionDocMime());
        payload.put("size", s.getSanctionDocSize());

        String mime = s.getSanctionDocMime() == null ? "" : s.getSanctionDocMime().toLowerCase();
        String name = s.getSanctionDocName() == null ? "" : s.getSanctionDocName().toLowerCase();

        if (mime.contains("pdf") || name.endsWith(".pdf")) {
            // Nothing to convert — the viewer fetches the bytes directly.
            payload.put("kind", "PDF");
            return payload;
        }

        payload.put("kind", "HTML");
        try {
            payload.put("html", htmlRenderer.toHtml(data));
        } catch (Throwable t) {
            // Formatted conversion touches a lot of the POI styling API, and a
            // document that trips any part of it shouldn't cost the user the
            // ability to read the letter. Fall back to the plain text the
            // importer already extracts successfully from this same file.
            log.warn("HTML conversion failed for sanction {}; falling back to text", sanctionId, t);
            String text = SanctionDocExtractor.loadText(data, true);
            payload.put("html", asPlainHtml(text));
            payload.put("degraded", true);
        }
        return payload;
    }

    /** Wrap extracted text as escaped paragraphs — the readable last resort. */
    private String asPlainHtml(String text) {
        if (text == null || text.isBlank()) {
            return "<div class=\"docx-body\"><p class=\"docx-p\">"
                 + "This document contains no readable text.</p></div>";
        }
        StringBuilder sb = new StringBuilder("<div class=\"docx-body\">");
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                sb.append("<p class=\"docx-spacer\"></p>");
            } else {
                sb.append("<p class=\"docx-p\">").append(escapeHtml(line)).append("</p>");
            }
        }
        return sb.append("</div>").toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Categories present across live sanctions, for the registry filter. */
    public List<String> getCategories() {
        return sanctionRepo.findDistinctCategories();
    }

    @Transactional(readOnly = true)
    public BorrowerSanctionEntity getDocumentEntity(Long sanctionId) throws CustomException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        byte[] data = sanctionRepo.findDocData(sanctionId);
        if (data == null || data.length == 0) {
            throw new CustomException("No document stored for this sanction");
        }
        s.setSanctionDocData(data);
        return s;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Mapping
    // ════════════════════════════════════════════════════════════════════════

    private void applyBorrower(BorrowerEntity b, BorrowerWrapper in) {
        b.setBorrowerName(SanctionValueParser.clean(in.getBorrowerName()));
        b.setCin(SanctionValueParser.clean(in.getCin()));
        b.setPan(SanctionValueParser.clean(in.getPan()));
        b.setSponsorName(SanctionValueParser.clean(in.getSponsorName()));
        b.setPromoterName(SanctionValueParser.clean(in.getPromoterName()));
        b.setGuarantorName(SanctionValueParser.clean(in.getGuarantorName()));
        b.setGroupName(SanctionValueParser.clean(in.getGroupName()));
        b.setBorrowerCategory(SanctionValueParser.clean(in.getBorrowerCategory()));
        b.setBorrowerSubCategory(SanctionValueParser.clean(in.getBorrowerSubCategory()));
        b.setRegisteredAddress(SanctionValueParser.clean(in.getRegisteredAddress()));
        b.setCity(SanctionValueParser.clean(in.getCity()));
        b.setState(SanctionValueParser.clean(in.getState()));
        b.setPincode(SanctionValueParser.clean(in.getPincode()));
        b.setDistrict(SanctionValueParser.clean(in.getDistrict()));
        b.setContactPerson(SanctionValueParser.clean(in.getContactPerson()));
        b.setContactEmail(SanctionValueParser.clean(in.getContactEmail()));
        b.setContactPhone(SanctionValueParser.clean(in.getContactPhone()));
        b.setNotes(SanctionValueParser.clean(in.getNotes()));
        if (in.getProjectId() != null) b.setProjectId(in.getProjectId());
    }

    private void applySanction(BorrowerSanctionEntity e, BorrowerSanctionWrapper in) {
        e.setRefNo(SanctionValueParser.clean(in.getRefNo()));
        e.setSanctionDate(SanctionValueParser.parseDate(in.getSanctionDate()));
        e.setLenderName(SanctionValueParser.clean(in.getLenderName()));
        e.setProjectName(SanctionValueParser.clean(in.getProjectName()));
        e.setCategory(SanctionValueParser.clean(in.getCategory()));
        e.setLocation(SanctionValueParser.clean(in.getLocation()));

        // Money on this form is quoted in crore, so a unit-less number scales.
        e.setProjectCost(SanctionValueParser.parseMoneyCrore(in.getProjectCost()));
        e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore(in.getSanctionedAmount()));
        e.setDebtAmount(SanctionValueParser.parseMoneyCrore(in.getDebtAmount()));
        e.setEquityAmount(SanctionValueParser.parseMoneyCrore(in.getEquityAmount()));
        e.setDebtPct(SanctionValueParser.parsePct(in.getDebtPct()));
        e.setEquityPct(SanctionValueParser.parsePct(in.getEquityPct()));
        e.setDebtEquityRatio(SanctionValueParser.clean(in.getDebtEquityRatio()));

        e.setBaseRatePct(SanctionValueParser.parsePct(in.getBaseRatePct()));
        e.setSpreadPct(SanctionValueParser.parsePct(in.getSpreadPct()));
        e.setRoiPct(SanctionValueParser.parsePct(in.getRoiPct()));
        e.setInterestRateText(SanctionValueParser.clean(in.getInterestRateText()));
        // Prefer an explicitly supplied percentage; otherwise read it off the phrase.
        e.setInterestRatePct(in.getInterestRatePct() != null
                ? SanctionValueParser.parseDecimal(in.getInterestRatePct())
                : SanctionValueParser.parseRatePct(in.getInterestRateText()));

        e.setTechnology(SanctionValueParser.clean(in.getTechnology()));
        e.setVillage(SanctionValueParser.clean(in.getVillage()));
        e.setDistrict(SanctionValueParser.clean(in.getDistrict()));
        e.setInstrument(SanctionValueParser.clean(in.getInstrument()));

        e.setCoObligators(SanctionValueParser.clean(in.getCoObligators()));
        e.setPledgeOfSharesPct(SanctionValueParser.parsePct(in.getPledgeOfSharesPct()));

        e.setMinDscr(SanctionValueParser.parseMultiple(in.getMinDscr()));
        e.setDsra(SanctionValueParser.clean(in.getDsra()));
        e.setIsra(SanctionValueParser.clean(in.getIsra()));
        e.setCashSweep(SanctionValueParser.clean(in.getCashSweep()));

        e.setTenorText(SanctionValueParser.clean(in.getTenorText()));
        e.setTenorMonths(in.getTenorMonths() != null
                ? SanctionValueParser.parseInt(in.getTenorMonths())
                : SanctionValueParser.parseTenorMonths(in.getTenorText()));
        e.setMoratoriumMonths(in.getMoratoriumMonths() != null
                ? SanctionValueParser.parseInt(in.getMoratoriumMonths())
                : SanctionValueParser.parseMoratoriumMonths(in.getTenorText()));

        e.setDisbursementDate(SanctionValueParser.parseDate(in.getDisbursementDate()));
        e.setRepaymentStartDate(SanctionValueParser.parseDate(in.getRepaymentStartDate()));
        e.setRepaymentEndDate(SanctionValueParser.parseDate(in.getRepaymentEndDate()));
        e.setScheduledCod(SanctionValueParser.parseDate(in.getScheduledCod()));

        e.setPlfPct(SanctionValueParser.parsePct(in.getPlfPct()));
        e.setTariffPerUnit(SanctionValueParser.parseDecimal(in.getTariffPerUnit()));

        if (!SanctionValueParser.isBlank(in.getStatus()))  e.setStatus(in.getStatus());
        if (!SanctionValueParser.isBlank(in.getSource()))  e.setSource(in.getSource());
        if (!SanctionValueParser.isBlank(in.getExtractionEngine())) {
            e.setExtractionEngine(in.getExtractionEngine());
        }
    }

    private BorrowerWrapper toWrapper(BorrowerEntity b) {
        BorrowerWrapper w = new BorrowerWrapper();
        w.setId(b.getId());
        w.setBorrowerName(b.getBorrowerName());
        w.setCin(b.getCin());
        w.setPan(b.getPan());
        w.setSponsorName(b.getSponsorName());
        w.setPromoterName(b.getPromoterName());
        w.setGuarantorName(b.getGuarantorName());
        w.setGroupName(b.getGroupName());
        w.setBorrowerCategory(b.getBorrowerCategory());
        w.setBorrowerSubCategory(b.getBorrowerSubCategory());
        w.setRegisteredAddress(b.getRegisteredAddress());
        w.setCity(b.getCity());
        w.setState(b.getState());
        w.setPincode(b.getPincode());
        w.setDistrict(b.getDistrict());
        w.setContactPerson(b.getContactPerson());
        w.setContactEmail(b.getContactEmail());
        w.setContactPhone(b.getContactPhone());
        w.setNotes(b.getNotes());
        w.setProjectId(b.getProjectId());
        w.setCreatedAt(b.getCreatedAt() == null ? null : b.getCreatedAt().format(TS));
        w.setUpdatedAt(b.getUpdatedAt() == null ? null : b.getUpdatedAt().format(TS));

        // Deliberately still the seven KYC-pack fields. The registry-sheet
        // columns added alongside them are not part of "identity complete", and
        // counting them would flip every existing Complete chip to "7 of 13".
        int filled = 0;
        if (!SanctionValueParser.isBlank(b.getCin()))               filled++;
        if (!SanctionValueParser.isBlank(b.getPan()))               filled++;
        if (!SanctionValueParser.isBlank(b.getSponsorName()))       filled++;
        if (!SanctionValueParser.isBlank(b.getRegisteredAddress())) filled++;
        if (!SanctionValueParser.isBlank(b.getContactPerson()))     filled++;
        if (!SanctionValueParser.isBlank(b.getContactEmail()))      filled++;
        if (!SanctionValueParser.isBlank(b.getContactPhone()))      filled++;
        w.setIdentityFilled(filled);
        w.setIdentityTotal(IDENTITY_TOTAL);

        return w;
    }

    private BorrowerSanctionWrapper toWrapper(BorrowerSanctionEntity e) {
        BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
        w.setId(e.getId());
        w.setBorrowerId(e.getBorrowerId());
        w.setRefNo(e.getRefNo());
        w.setSanctionDate(SanctionValueParser.formatDate(e.getSanctionDate()));
        w.setLenderName(e.getLenderName());
        w.setProjectName(e.getProjectName());
        w.setCategory(e.getCategory());
        w.setLocation(e.getLocation());
        w.setProjectCost(SanctionValueParser.formatCrore(e.getProjectCost()));
        w.setSanctionedAmount(SanctionValueParser.formatCrore(e.getSanctionedAmount()));
        w.setDebtAmount(SanctionValueParser.formatCrore(e.getDebtAmount()));
        w.setEquityAmount(SanctionValueParser.formatCrore(e.getEquityAmount()));
        w.setDebtPct(SanctionValueParser.formatPct(e.getDebtPct()));
        w.setEquityPct(SanctionValueParser.formatPct(e.getEquityPct()));
        w.setDebtEquityRatio(e.getDebtEquityRatio());

        w.setBaseRatePct(SanctionValueParser.formatPct(e.getBaseRatePct()));
        w.setSpreadPct(SanctionValueParser.formatPct(e.getSpreadPct()));
        w.setRoiPct(SanctionValueParser.formatPct(e.getRoiPct()));
        w.setInterestRatePct(SanctionValueParser.str(e.getInterestRatePct()));
        w.setInterestRateText(e.getInterestRateText());

        w.setTechnology(e.getTechnology());
        w.setVillage(e.getVillage());
        w.setDistrict(e.getDistrict());
        w.setInstrument(e.getInstrument());

        w.setCoObligators(e.getCoObligators());
        w.setPledgeOfSharesPct(SanctionValueParser.formatPct(e.getPledgeOfSharesPct()));

        w.setMinDscr(SanctionValueParser.formatMultiple(e.getMinDscr()));
        w.setDsra(e.getDsra());
        w.setIsra(e.getIsra());
        w.setCashSweep(e.getCashSweep());

        w.setTenorText(e.getTenorText());
        w.setTenorMonths(SanctionValueParser.str(e.getTenorMonths()));
        w.setMoratoriumMonths(SanctionValueParser.str(e.getMoratoriumMonths()));

        w.setDisbursementDate(SanctionValueParser.formatDate(e.getDisbursementDate()));
        w.setRepaymentStartDate(SanctionValueParser.formatDate(e.getRepaymentStartDate()));
        w.setRepaymentEndDate(SanctionValueParser.formatDate(e.getRepaymentEndDate()));
        w.setScheduledCod(SanctionValueParser.formatDate(e.getScheduledCod()));

        w.setPlfPct(SanctionValueParser.formatPct(e.getPlfPct()));
        w.setTariffPerUnit(SanctionValueParser.formatTariff(e.getTariffPerUnit()));

        w.setStatus(e.getStatus());
        w.setSource(e.getSource());
        w.setExtractionEngine(e.getExtractionEngine());
        w.setSanctionDocName(e.getSanctionDocName());
        w.setSanctionDocMime(e.getSanctionDocMime());
        w.setSanctionDocSize(e.getSanctionDocSize());
        w.setHasDocument(e.getSanctionDocSize() != null && e.getSanctionDocSize() > 0);
        w.setCreatedAt(e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
        w.setUpdatedAt(e.getUpdatedAt() == null ? null : e.getUpdatedAt().format(TS));

        derived.apply(e, w);
        // Printed wins; this only fills registry-sheet columns still blank.
        derived.fillGaps(e, w);
        return w;
    }

    /** Serialise the parser's original output for the audit column. */
    public String toJson(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            return null;
        }
    }
}