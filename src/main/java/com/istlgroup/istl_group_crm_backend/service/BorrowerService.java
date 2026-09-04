package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerAliasEntity;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerEntity;
import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.entity.CompanyGroupEntity;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerAliasRepo;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerRepo;
import com.istlgroup.istl_group_crm_backend.repo.BorrowerSanctionRepo;
import com.istlgroup.istl_group_crm_backend.repo.CompanyGroupRepo;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.CompanyGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.CompanyMatchWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PagedResponseWrapper;

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
    @Autowired private SanctionDocOcrService ocrService;
    @Autowired private SanctionDerivedCalculator derived;
    @Autowired private SanctionDocHtmlRenderer htmlRenderer;
    @Autowired private CompanyGroupRepo companyGroupRepo;
    @Autowired private BorrowerAliasRepo borrowerAliasRepo;
    @Autowired private RoleHierarchyService roleHierarchyService;
    @Autowired private TeamRepository teamRepository;

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

        String engine = docx ? "TABLE" : "REGEX";
        // Only ever populated below, for a PDF whose text layer was unusable —
        // stays empty for every digital PDF and every DOCX, which is what
        // keeps the AI-text and Vision tiers further down unreachable for
        // those, exactly as before this method gained OCR support.
        List<byte[]> ocrPages = List.of();
        boolean ocrUsed = false;

        // A PDF with no text layer yields nothing for either tier — that's the
        // scanned-document case this class previously gave up on immediately.
        // Try local OCR first (free, no Groq) before falling back to the same
        // "enter manually" message as before.
        if (!docx && text.strip().length() < 200) {
            log.info("Sanction parse: PDF text layer insufficient ({} char(s)) — trying local OCR",
                    text.strip().length());
            ocrPages = ocrService.renderPagesToPng(bytes);
            StringBuilder ocrText = new StringBuilder();
            Map<String, Object> ocrDeterministic = Map.of();
            for (int i = 0; i < ocrPages.size(); i++) {
                String pageText = ocrService.ocrPage(ocrPages.get(i));
                if (pageText == null || pageText.isBlank()) continue;
                ocrText.append(pageText).append("\n\n");
                ocrDeterministic = docExtractor.extractFromPdfText(ocrText.toString());
                if (docExtractor.isSufficient(ocrDeterministic)) {
                    log.info("Sanction parse: OCR sufficient after page {}/{}", i + 1, ocrPages.size());
                    break;
                }
            }

            if (!ocrText.toString().isBlank()) {
                text = ocrText.toString();
                deterministic = ocrDeterministic;
                engine = "OCR";
                ocrUsed = true;
                log.info("Sanction parse: OCR produced {} field(s) across up to {} page(s)",
                        deterministic.size(), ocrPages.size());
            } else {
                log.info("Sanction parse: OCR produced no usable text (Tesseract unavailable, "
                        + "or the scan itself is unreadable)");
                throw new CustomException(
                        "This PDF has no readable text layer (it looks like a scan), so fields "
                      + "can't be extracted automatically. Please enter them manually.");
            }
        }

        if (docExtractor.isSufficient(deterministic)) {
            // DSCR is a near-universal covenant — unlike a genuinely optional
            // field such as PLF or ISRA, a letter that sanctions a term loan but
            // yields no minDscr/avgDscr from the deterministic tier is more
            // likely a phrasing the regex missed than a letter that truly omits
            // the covenant. So even though the parse is otherwise sufficient,
            // give the AI one targeted shot at whichever of the two is missing
            // rather than let it default to blank.
            if (!deterministic.containsKey("minDscr") || !deterministic.containsKey("avgDscr")) {
                Map<String, Object> withAiDscr = fillMissingDscrFromAi(deterministic, text, engine);
                if (withAiDscr != null) return withAiDscr;
            }
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
                String mergedEngine = deterministic.isEmpty() ? "AI" : "MIXED";
                if (ocrUsed) mergedEngine = "OCR+" + mergedEngine;

                // Vision is reachable ONLY down the scanned-PDF path (ocrUsed
                // and ocrPages both require it) and only once OCR plus the
                // Groq text tier above still haven't cleared isSufficient — a
                // digital PDF or DOCX never reaches this branch, so its
                // behaviour is completely unchanged by adding it.
                if (ocrUsed && !ocrPages.isEmpty() && !docExtractor.isSufficient(merged)) {
                    log.info("Sanction parse: OCR+AI text tier still short of required fields — "
                            + "trying Groq Vision as a last resort");
                    Map<String, Object> visioned = tryVisionFallback(merged, ocrPages);
                    if (visioned != null) {
                        return withMeta(visioned, mergedEngine + "+VISION", aiOnly);
                    }
                }
                return withMeta(merged, mergedEngine, aiOnly);
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

    /** Never sends more than this many pages to Groq Vision, even across retries. */
    private static final int MAX_VISION_PAGES = 3;

    /**
     * Last-resort Groq Vision pass over already-rendered scanned pages —
     * reached only from {@link #parseSanction}'s scanned-PDF path, only when
     * local OCR plus the existing text-AI fallback still haven't produced the
     * minimum required fields. Pages are sent one at a time (never the whole
     * document), stopping the moment the merged result is sufficient, capped
     * at {@link #MAX_VISION_PAGES} — the smallest amount of Vision usage that
     * can still resolve a difficult scan.
     *
     * <p>Returns {@code null} if Vision adds nothing at all (every page fails
     * or comes back empty), so the caller can fall back to whatever it already
     * had rather than losing it.
     */
    private Map<String, Object> tryVisionFallback(Map<String, Object> merged, List<byte[]> pages) {
        if (pages.isEmpty()) return null;
        int cap = Math.min(pages.size(), MAX_VISION_PAGES);
        Map<String, Object> augmented = new LinkedHashMap<>(merged);
        boolean any = false;
        for (int i = 0; i < cap; i++) {
            try {
                String b64 = Base64.getEncoder().encodeToString(pages.get(i));
                Map<String, Object> vision = aiExtractor.extractFromImage(b64, "image/png");
                if (vision != null && !vision.isEmpty()) {
                    // Never overwrite a value OCR/text-AI already trusted more —
                    // Vision here only fills gaps those tiers left behind.
                    for (Map.Entry<String, Object> e : vision.entrySet()) {
                        augmented.putIfAbsent(e.getKey(), e.getValue());
                    }
                    any = true;
                }
            } catch (Exception e) {
                log.warn("Sanction parse: Vision fallback failed on page {}/{}: {}",
                        i + 1, cap, e.getMessage());
            }
            if (docExtractor.isSufficient(augmented)) {
                log.info("Sanction parse: Vision fallback sufficient after page {}/{}", i + 1, cap);
                break;
            }
        }
        return any ? augmented : null;
    }

    /**
     * A scoped AI call for just the DSCR covenant(s), used when the rest of the
     * letter parsed well enough that the full AI fallback in {@link #parseSanction}
     * never ran. Returns {@code null} (meaning "nothing changed, fall through to
     * the deterministic result as-is") when the AI call fails or doesn't add
     * either field — this must never make a sufficient parse worse.
     */
    private Map<String, Object> fillMissingDscrFromAi(Map<String, Object> deterministic,
                                                        String text, String engine) {
        try {
            Map<String, Object> ai = aiExtractor.extractFromText(text);
            if (ai == null || ai.isEmpty()) return null;

            List<String> filled = new ArrayList<>();
            for (String key : List.of("minDscr", "avgDscr")) {
                if (!deterministic.containsKey(key) && ai.containsKey(key)) {
                    deterministic.put(key, ai.get(key));
                    filled.add(key);
                }
            }
            if (filled.isEmpty()) return null;

            log.info("Sanction parse: {} tier missed {}; AI filled it in", engine, filled);
            return withMeta(deterministic, "MIXED", filled);
        } catch (Exception e) {
            log.warn("AI DSCR fallback failed: {}", e.getMessage());
            return null;
        }
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

        // Never claim extraction found something it didn't: if neither tier
        // reported a moratorium-interest treatment, default to Served but say
        // so out-of-band — the same "verbatim, no guessing" rule the rest of
        // this method already applies to _duplicateRefNo.
        if (!out.containsKey("interestDuringMoratorium")) {
            out.put("interestDuringMoratorium", "SERVICED");
            out.put("_interestMoratoriumDefaulted", true);
        } else {
            out.put("_interestMoratoriumDefaulted", false);
        }

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

    public List<BorrowerWrapper> getAll(Long userId, String userRole, String search, String category) {
        // One query handles all four states; blank means "don't filter".
        String q = SanctionValueParser.isBlank(search) ? null : search.trim();
        String c = SanctionValueParser.isBlank(category) ? null : category.trim();
        List<BorrowerEntity> rows = borrowerRepo.search(q, c);

        // Resolved once, not once per row inside borrowerInScope — see the
        // Javadoc on inScope().
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<BorrowerEntity> inScopeRows = rows.stream()
                .filter(b -> borrowerInScope(b, userId, level))
                .collect(Collectors.toList());

        // One query for every in-scope borrower's sanctions instead of one
        // query per row (this used to be the dominant repeated query on this
        // endpoint) — grouped by borrower afterwards; within each borrower's
        // own group the rows stay in the same sanction-date-desc order the
        // old per-borrower query gave, so "first in the list" is still its
        // latest sanction.
        Map<Long, List<BorrowerSanctionEntity>> sanctionsByBorrower = new LinkedHashMap<>();
        if (!inScopeRows.isEmpty()) {
            List<Long> ids = inScopeRows.stream().map(BorrowerEntity::getId).collect(Collectors.toList());
            for (BorrowerSanctionEntity s
                    : sanctionRepo.findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(ids)) {
                sanctionsByBorrower.computeIfAbsent(s.getBorrowerId(), k -> new ArrayList<>()).add(s);
            }
        }

        List<BorrowerWrapper> out = new ArrayList<>();
        for (BorrowerEntity b : inScopeRows) {
            BorrowerWrapper w = toWrapper(b);
            List<BorrowerSanctionEntity> sanctions = sanctionsByBorrower.getOrDefault(b.getId(), List.of());
            if (!sanctions.isEmpty()) {
                BorrowerSanctionEntity latest = sanctions.get(0);
                BorrowerSanctionWrapper lw = toWrapper(latest, b);
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

    public BorrowerWrapper getById(Long userId, String userRole, Long id) throws CustomException {
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (!borrowerInScope(b, userId, level)) {
            throw new CustomException("Borrower not found");
        }
        BorrowerWrapper w = buildBorrowerWrapper(b);
        w.setCanEditBorrower(inScope(b.getCreatedBy(), userId, level));
        return w;
    }

    /**
     * The unscoped core of {@link #getById} — reused by write methods below
     * to build their response for a borrower the caller just legitimately
     * touched (created it, passed {@link #assertWriteScope}, or attached a
     * sanction via the deliberately cross-team {@link #matchBorrower}/
     * {@link #resolveBorrower} flow) without re-running the visibility
     * check a moment after the operation that already established it's
     * allowed.
     */
    private BorrowerWrapper buildBorrowerWrapper(Long id) throws CustomException {
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        return buildBorrowerWrapper(b);
    }

    private BorrowerWrapper buildBorrowerWrapper(BorrowerEntity b) {
        BorrowerWrapper w = toWrapper(b);
        w.setAliases(borrowerAliasRepo.findByBorrowerId(b.getId()).stream()
                .map(BorrowerAliasEntity::getAliasName).collect(Collectors.toList()));
        for (BorrowerSanctionEntity s :
                sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(b.getId())) {
            w.getSanctions().add(toWrapper(s, b));
        }
        // Derived from the sanctions just loaded above — see deriveStatusLabel.
        w.setStatus(deriveStatusLabel(w.getSanctions().stream()
                .anyMatch(s -> isActiveSanction(s.getActiveStatus()))));
        return w;
    }

    @Transactional
    public BorrowerWrapper createBorrower(BorrowerWrapper in, Long userId) throws CustomException {
        if (SanctionValueParser.isBlank(in.getBorrowerName())) {
            throw new CustomException("Borrower name is required");
        }
        // Normalized here (not just inside applyBorrower) so assertCinFree's
        // collision check runs against the same uppercase, stripped value
        // that actually ends up on file — a raw value differing only in
        // case/stray whitespace from an existing CIN must still collide.
        in.setCin(SanctionValueParser.requireValidCin(in.getCin()));
        assertCinFree(in.getCin(), null);
        assertGroupValid(in.getGroupId());

        BorrowerEntity b = new BorrowerEntity();
        applyBorrower(b, in);
        b.setCreatedBy(userId);
        BorrowerEntity saved = borrowerRepo.save(b);
        syncAliases(saved.getId(), in.getAliases(), userId);
        return buildBorrowerWrapper(saved.getId());
    }

    @Transactional
    public BorrowerWrapper updateBorrower(Long id, BorrowerWrapper in, Long userId, String userRole)
            throws CustomException {
        assertWriteScope(userId, userRole, id);
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        if (SanctionValueParser.isBlank(in.getBorrowerName())) {
            throw new CustomException("Borrower name is required");
        }
        in.setCin(SanctionValueParser.requireValidCin(in.getCin()));
        assertCinFree(in.getCin(), id);
        assertGroupValid(in.getGroupId());

        applyBorrower(b, in);
        b.setUpdatedBy(userId);
        BorrowerEntity saved = borrowerRepo.save(b);
        if (in.getAliases() != null) syncAliases(saved.getId(), in.getAliases(), userId);
        return buildBorrowerWrapper(saved.getId());
    }

    /**
     * Move a company between groups, or in/out of standalone, without
     * touching anything sanction-related — {@code group_id} lives only on
     * {@code borrowers}, so its sanctions, documents and repayment schedules
     * are untouched by construction.
     */
    @Transactional
    public BorrowerWrapper updateBorrowerHierarchy(Long id, Long groupId, Boolean isSubsidiary,
                                                     Boolean isSpv, Long userId, String userRole) throws CustomException {
        assertWriteScope(userId, userRole, id);
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        assertGroupValid(groupId);
        b.setGroupId(groupId);
        if (isSubsidiary != null) b.setIsSubsidiary(isSubsidiary);
        if (isSpv != null) b.setIsSpv(isSpv);
        b.setUpdatedBy(userId);
        return buildBorrowerWrapper(borrowerRepo.save(b).getId());
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
    public void deleteBorrower(Long id, Long userId, String userRole) throws CustomException {
        // findById (not ...AndDeletedAtIsNull) so an already soft-deleted
        // borrower can still be purged for good — same reason this can't
        // just call assertWriteScope, which only looks at non-deleted rows.
        BorrowerEntity b = borrowerRepo.findById(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        if (!inScope(b.getCreatedBy(), userId, roleHierarchyService.getLevelOrder(userRole))) {
            throw new CustomException("Borrower not found");
        }

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

    /** Sanctions associated directly with this Group (not any child company's) — purged before the Group itself. */
    private void deleteGroupOwnSanctions(Long groupId) {
        em.createNativeQuery("DELETE FROM borrower_sanctions WHERE group_id = :id")
          .setParameter("id", groupId)
          .executeUpdate();
    }

    /**
     * Deletes a Parent Group or Sub Group along with everything under it: every
     * company sitting directly in it (each purged exactly the way
     * {@link #deleteBorrower} purges one — sanctions and documents with it,
     * aliases cascading via the FK), and, for a Parent Group, every Sub Group
     * beneath it and everything under those in turn. Nothing is left pointing
     * at a group that no longer exists.
     *
     * <p>Children are removed before the parent so {@code fk_company_group_parent}
     * (ON DELETE RESTRICT) never blocks the delete.
     */
    @Transactional
    public void deleteGroup(Long id, Long userId) throws CustomException {
        companyGroupRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Group not found"));

        // Sub Groups directly under this one -- only relevant when id is a
        // Parent Group; a Sub Group can never have children of its own
        // (enforced in createGroup/updateGroup).
        for (CompanyGroupEntity sub
                : companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(id)) {
            deleteCompaniesInGroup(sub.getId());
            deleteGroupOwnSanctions(sub.getId());
            em.createNativeQuery("DELETE FROM company_groups WHERE id = :id")
              .setParameter("id", sub.getId())
              .executeUpdate();
        }

        deleteCompaniesInGroup(id);
        deleteGroupOwnSanctions(id);

        em.createNativeQuery("DELETE FROM company_groups WHERE id = :id")
          .setParameter("id", id)
          .executeUpdate();
    }

    /** Every company sitting directly under one group, purged the same way {@link #deleteBorrower} purges one. */
    private void deleteCompaniesInGroup(Long groupId) {
        for (BorrowerEntity b : borrowerRepo.findByGroupId(groupId)) {
            deleteByBorrowerId("borrower_sanctions", b.getId());
            em.createNativeQuery("DELETE FROM borrowers WHERE id = :id")
              .setParameter("id", b.getId())
              .executeUpdate();
        }
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

    private void assertGroupValid(Long groupId) throws CustomException {
        if (groupId == null) return;
        companyGroupRepo.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new CustomException("Selected group not found"));
    }

    /** Replaces a borrower's alias set wholesale — simplest correct sync for a short list. */
    private void syncAliases(Long borrowerId, List<String> aliases, Long userId) {
        List<BorrowerAliasEntity> existing = borrowerAliasRepo.findByBorrowerId(borrowerId);
        if (!existing.isEmpty()) borrowerAliasRepo.deleteAll(existing);
        if (aliases == null) return;
        for (String a : aliases) {
            if (SanctionValueParser.isBlank(a)) continue;
            BorrowerAliasEntity e = new BorrowerAliasEntity();
            e.setBorrowerId(borrowerId);
            e.setAliasName(a.trim());
            e.setCreatedBy(userId);
            borrowerAliasRepo.save(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Company hierarchy — Parent Groups, Sub Groups, matching, the tree
    // ════════════════════════════════════════════════════════════════════════

    private static String companyTypeLabel(Boolean isSubsidiary, Boolean isSpv) {
        boolean sub = Boolean.TRUE.equals(isSubsidiary);
        boolean spv = Boolean.TRUE.equals(isSpv);
        if (sub && spv) return "Subsidiary + SPV";
        if (sub) return "Subsidiary";
        if (spv) return "SPV";
        return "Standalone";
    }

    /** The Parent Group / Sub Group a company's {@code group_id} resolves to. */
    private static class GroupPath {
        Long parentGroupId; String parentGroupName;
        Long subGroupId; String subGroupName;
    }

    private GroupPath resolveGroupPath(Long groupId) {
        GroupPath p = new GroupPath();
        if (groupId == null) return p;
        CompanyGroupEntity g = companyGroupRepo.findByIdAndDeletedAtIsNull(groupId).orElse(null);
        if (g == null) return p;
        if (g.getParentGroupId() == null) {
            p.parentGroupId = g.getId();
            p.parentGroupName = g.getGroupName();
            return p;
        }
        p.subGroupId = g.getId();
        p.subGroupName = g.getGroupName();
        CompanyGroupEntity parent = companyGroupRepo.findByIdAndDeletedAtIsNull(g.getParentGroupId()).orElse(null);
        if (parent != null) {
            p.parentGroupId = parent.getId();
            p.parentGroupName = parent.getGroupName();
        }
        return p;
    }

    private CompanyGroupWrapper toGroupWrapper(CompanyGroupEntity g) {
        CompanyGroupWrapper w = new CompanyGroupWrapper();
        w.setId(g.getId());
        w.setGroupName(g.getGroupName());
        w.setParentGroupId(g.getParentGroupId());
        w.setType(g.getParentGroupId() == null ? "GROUP" : "SUB_GROUP");
        w.setCin(g.getCin());
        w.setRegisteredAddress(g.getRegisteredAddress());
        if (g.getParentGroupId() != null) {
            companyGroupRepo.findByIdAndDeletedAtIsNull(g.getParentGroupId())
                    .ifPresent(parent -> w.setParentGroupName(parent.getGroupName()));
        }
        w.setCreatedAt(g.getCreatedAt() == null ? null : g.getCreatedAt().format(TS));
        w.setUpdatedAt(g.getUpdatedAt() == null ? null : g.getUpdatedAt().format(TS));
        return w;
    }

    /** Top-level Parent Groups when {@code parentGroupId} is null, else the Sub Groups under it. */
    @Transactional(readOnly = true)
    public List<CompanyGroupWrapper> listGroups(Long parentGroupId) {
        List<CompanyGroupEntity> rows = parentGroupId == null
                ? companyGroupRepo.findByParentGroupIdIsNullAndDeletedAtIsNullOrderByGroupNameAsc()
                : companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(parentGroupId);
        return rows.stream().map(this::toGroupWrapper).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CompanyGroupWrapper> searchGroups(String q) {
        String query = SanctionValueParser.isBlank(q) ? "" : q.trim();
        return companyGroupRepo.search(query).stream().map(this::toGroupWrapper).collect(Collectors.toList());
    }

    @Transactional
    public CompanyGroupWrapper createGroup(CompanyGroupWrapper in, Long userId) throws CustomException {
        if (SanctionValueParser.isBlank(in.getGroupName())) {
            throw new CustomException("Group name is required");
        }
        // Shares its duplicate-name checks (against other groups AND against
        // existing companies/borrowers) with resolveBorrowerWithHierarchy's
        // own group-creation step — see createGroupChecked's own comment.
        Long id = createGroupChecked(in.getGroupName().trim(), in.getParentGroupId(),
                in.getCin(), in.getRegisteredAddress(), userId);
        return toGroupWrapper(companyGroupRepo.findById(id).orElseThrow(
                () -> new CustomException("Group not found")));
    }

    /** Rename a group and/or move it under a different Parent Group (or make it top-level). */
    @Transactional
    public CompanyGroupWrapper updateGroup(Long id, CompanyGroupWrapper in, Long userId) throws CustomException {
        CompanyGroupEntity g = companyGroupRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Group not found"));
        if (SanctionValueParser.isBlank(in.getGroupName())) {
            throw new CustomException("Group name is required");
        }
        Long newParentId = in.getParentGroupId();
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new CustomException("A group cannot be its own parent.");
            }
            CompanyGroupEntity parent = companyGroupRepo.findByIdAndDeletedAtIsNull(newParentId)
                    .orElseThrow(() -> new CustomException("Parent group not found"));
            if (parent.getParentGroupId() != null) {
                throw new CustomException("A Sub Group cannot itself be nested under another Sub Group.");
            }
            if (!companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(id).isEmpty()) {
                throw new CustomException(
                        "This group has Sub Groups under it and cannot itself become a Sub Group.");
            }
        }
        String name = in.getGroupName().trim();
        Optional<CompanyGroupEntity> dupe = companyGroupRepo.findByExactNameAndParent(name, newParentId);
        if (dupe.isPresent() && !dupe.get().getId().equals(id)) {
            throw new CustomException("Another group with that name already exists in that scope.");
        }
        String validCin = SanctionValueParser.requireValidCin(in.getCin());
        assertGroupCinFree(validCin, id);
        g.setGroupName(name);
        g.setParentGroupId(newParentId);
        g.setCin(validCin);
        g.setRegisteredAddress(SanctionValueParser.isBlank(in.getRegisteredAddress())
                ? null : SanctionValueParser.clean(in.getRegisteredAddress()));
        g.setUpdatedBy(userId);
        return toGroupWrapper(companyGroupRepo.save(g));
    }

    // ── Role Hierarchy visibility scoping ──────────────────────────────────
    // Same mechanism already used for Leads/Customers/Order Book/Proposals
    // (see LeadsService): role_hierarchy.level_order decides the tier,
    // team_members decides who's on the same team at the middle tier. A
    // borrower/sanction with no createdBy (legacy/bulk-imported data) is
    // only ever in scope at level <= 2 — createdBy == userId and
    // memberIds.contains(createdBy) both simply never match null, so no
    // special-casing is needed for that.
    //
    // Deliberately NOT applied to matchBorrower() (see its own Javadoc) —
    // duplicate-detection during import must search every borrower
    // company-wide regardless of who's asking, or a scoped-out user could
    // unknowingly create a duplicate company that already exists under
    // someone else's scope. Not applied to getCategories() either — that
    // only returns distinct category strings, not borrower records.
    private List<Long> resolveTeamMemberIds(Long userId) {
        List<Long> ids = teamRepository.findTeamMemberIdsByUserId(userId);
        return ids.isEmpty() ? List.of(userId) : ids;
    }

    /**
     * {@code level} is {@link RoleHierarchyService#getLevelOrder} resolved
     * from the caller's role — every method below that scope-checks more
     * than one record resolves it exactly ONCE per request and threads it
     * through as a plain int, rather than each check re-querying
     * {@code role_hierarchy} for a value that cannot change mid-request.
     * That single change is what previously showed up as dozens of
     * identical {@code SELECT level_order FROM role_hierarchy WHERE
     * role_name = ?} calls per page load — see {@link #getHierarchyPage}
     * and friends below.
     */
    private boolean inScope(Long recordCreatedBy, Long userId, int level) {
        if (level <= 2) return true;
        if (level == 3) return recordCreatedBy != null && resolveTeamMemberIds(userId).contains(recordCreatedBy);
        return Objects.equals(recordCreatedBy, userId);
    }

    /**
     * A borrower is visible if its own createdBy is in scope (the base rule
     * above) OR the caller personally has at least one sanction of their own
     * (in their own scope) attached under it. Without this second clause, a
     * sanction legitimately attached via the always-global Company Match
     * (matchBorrower/resolveBorrower stay unscoped on purpose — see their own
     * Javadoc) would vanish from the importer's own view the instant they
     * saved it, even though nothing about who "owns" the borrower record
     * itself changed. Only affects visibility, never who can rename/delete/
     * move the borrower itself — see assertWriteScope for that, unchanged.
     */
    private boolean borrowerInScope(BorrowerEntity b, Long userId, int level) {
        if (inScope(b.getCreatedBy(), userId, level)) return true;
        return sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(b.getId()).stream()
                .anyMatch(s -> inScope(s.getCreatedBy(), userId, level));
    }

    /**
     * Same "visible via what's mine" reasoning as borrowerInScope, one level
     * up: a Parent/Sub Group is visible if its own createdBy is in scope, OR
     * it contains at least one borrower that's visible under borrowerInScope
     * (directly, or under one of its Sub Groups) — otherwise a borrower made
     * visible only because the caller attached their own sanction to it
     * would still never appear in the Hierarchy Tree/Page, which gate on the
     * group first, even though its own direct-by-id page would show it fine.
     */
    private boolean groupInScope(CompanyGroupEntity g, Long userId, int level) {
        if (inScope(g.getCreatedBy(), userId, level)) return true;
        if (borrowerRepo.findByGroupId(g.getId()).stream().anyMatch(b -> borrowerInScope(b, userId, level))) {
            return true;
        }
        if (g.getParentGroupId() == null) {
            for (CompanyGroupEntity sub
                    : companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(g.getId())) {
                if (borrowerRepo.findByGroupId(sub.getId()).stream()
                        .anyMatch(b -> borrowerInScope(b, userId, level))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Throws the same "not found" a caller would get for a nonexistent id — never reveals that an out-of-scope record exists. */
    private void assertWriteScope(Long userId, String userRole, Long borrowerId) throws CustomException {
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(borrowerId)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        if (!inScope(b.getCreatedBy(), userId, roleHierarchyService.getLevelOrder(userRole))) {
            throw new CustomException("Borrower not found");
        }
    }

    /**
     * Same "visible via what's mine" principle as borrowerInScope, applied to
     * a single sanction-level write (edit/delete/re-upload a letter) — the
     * borrower's own scope OR this specific sanction's own createdBy grants
     * access, so the importer can manage the sanction they personally
     * attached even when the borrower itself belongs to someone else.
     * Borrower-level writes (rename/delete/move the whole company) stay
     * strictly borrower-owner-gated via assertWriteScope — unaffected.
     */
    private void assertSanctionWriteScope(Long userId, String userRole, Long ownerCreatedBy,
            BorrowerSanctionEntity sanction) throws CustomException {
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (inScope(ownerCreatedBy, userId, level)) return;
        if (sanction != null && inScope(sanction.getCreatedBy(), userId, level)) return;
        throw new CustomException("Sanction not found");
    }

    /**
     * The creator of whichever record (borrower or group) a sanction is
     * directly attached to — a sanction always has exactly one of the two set.
     */
    private Long ownerCreatedByFor(BorrowerSanctionEntity s) throws CustomException {
        if (s.getBorrowerId() != null) {
            return borrowerRepo.findByIdAndDeletedAtIsNull(s.getBorrowerId())
                    .orElseThrow(() -> new CustomException("Sanction not found")).getCreatedBy();
        }
        return companyGroupRepo.findByIdAndDeletedAtIsNull(s.getGroupId())
                .orElseThrow(() -> new CustomException("Sanction not found")).getCreatedBy();
    }

    /**
     * Read-access guard for a single sanction's stored document (preview/
     * download) — uses borrowerInScope, the same rule that decides whether
     * this sanction appears at all in getById's response, so "can I see
     * this sanction on the page" and "can I fetch its letter directly by
     * URL" never disagree.
     */
    private void assertSanctionReadScope(Long userId, String userRole, BorrowerSanctionEntity sanction)
            throws CustomException {
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (sanction.getBorrowerId() != null) {
            BorrowerEntity borrower = borrowerRepo.findByIdAndDeletedAtIsNull(sanction.getBorrowerId())
                    .orElseThrow(() -> new CustomException("Sanction not found"));
            if (!borrowerInScope(borrower, userId, level)) {
                throw new CustomException("Sanction not found");
            }
            return;
        }
        CompanyGroupEntity group = companyGroupRepo.findByIdAndDeletedAtIsNull(sanction.getGroupId())
                .orElseThrow(() -> new CustomException("Sanction not found"));
        if (!groupInScope(group, userId, level)) {
            throw new CustomException("Sanction not found");
        }
    }

    /**
     * Rank candidate borrowers for a parsed sanction letter's identity.
     * Deliberately strict to exactly two identity fields — CIN and the
     * normalized legal name — and nothing softer: no alias lookup, no fuzzy
     * text-similarity fallback. The sanction-import decision this feeds
     * (CompanyMatchModal) needs a plain, explainable "CIN MATCHED" / "COMPANY
     * NAME MATCHED" / "NO MATCH" outcome, not a "possibly the same company"
     * guess a reviewer has to weigh — when this returns nothing, the reviewer
     * is meant to make an explicit allocation choice, not be nudged toward
     * one. Aliases remain a real, stored borrower feature (see {@code
     * saveBorrowerAliases}/{@code toWrapper}) — only their use as a MATCHING
     * signal here is removed. Never decides anything on its own — every
     * candidate is a suggestion for the reviewer to confirm or reject; see
     * the class comment on {@link CompanyMatchWrapper}.
     *
     * <p>Deliberately unscoped by Role Hierarchy — see the comment above
     * {@link #inScope}.
     */
    @Transactional(readOnly = true)
    public List<CompanyMatchWrapper> matchBorrower(BorrowerWrapper in) {
        List<CompanyMatchWrapper> out = new ArrayList<>();
        if (in == null) return out;

        // Priority 1: exact CIN — the natural key, so nothing else can outrank it.
        if (!SanctionValueParser.isBlank(in.getCin())) {
            Optional<BorrowerEntity> byCin =
                    borrowerRepo.findByCinIgnoreCaseAndDeletedAtIsNull(in.getCin().trim());
            if (byCin.isPresent()) {
                out.add(toMatch(byCin.get(), "CIN", 1.0));
                return out;
            }
        }

        String name = in.getBorrowerName();
        if (SanctionValueParser.isBlank(name)) return out;

        // Priority 2: normalized legal-name exact match. The only other
        // signal this method uses — no alias table lookup, no fuzzy scorer.
        for (BorrowerEntity b : borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc()) {
            if (CompanyNameMatcher.normalizedEquals(name, b.getBorrowerName())) {
                out.add(toMatch(b, "NAME", 1.0));
            }
        }
        return out;
    }

    private CompanyMatchWrapper toMatch(BorrowerEntity b, String confidence, Double score) {
        CompanyMatchWrapper m = new CompanyMatchWrapper(b.getId(), b.getBorrowerName(), b.getCin(), confidence, score);
        m.setCompanyType(companyTypeLabel(b.getIsSubsidiary(), b.getIsSpv()));
        GroupPath gp = resolveGroupPath(b.getGroupId());
        m.setParentGroupId(gp.parentGroupId);
        m.setParentGroupName(gp.parentGroupName);
        m.setSubGroupId(gp.subGroupId);
        m.setSubGroupName(gp.subGroupName);
        // Lets the Company Match screen show "Existing sanctions: N" per
        // candidate — the same finder rollupFor() already uses, just counted
        // rather than summed.
        m.setSanctionsCount(sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(b.getId()).size());
        return m;
    }

    /** Running totals for one company or one group node in {@link #getHierarchyTree()}. */
    private static class Rollup {
        int sanctionsCount;
        BigDecimal total = BigDecimal.ZERO;
        /**
         * Whether at least one sanction letter counted into this rollup has
         * {@code activeStatus == ACTIVE} — never the sanctions COUNT, never
         * anything about the company itself. This is the sole basis for a
         * Company's, Parent Group's, or Sub Group's own displayed
         * Active/Inactive status: a company is Active iff one of its own
         * sanctions is; a group is Active iff one of the sanctions anywhere
         * under its hierarchy (its companies', its Sub Groups' companies',
         * and its own/its Sub Groups' direct sanctions) is. See
         * {@link #deriveStatusLabel}.
         */
        boolean hasActiveSanction;
        /**
         * Only ever set on a single BORROWER's own rollup (from {@link
         * #rollupsFor}) — the id of that company's most recent sanction
         * letter by date, the "latest letter" convention this app already
         * uses elsewhere for a list row that must point at exactly one
         * sanction out of possibly several. This is what the registry's
         * per-company Status column's editable badge actually changes —
         * never every sanction the company has, and meaningless once summed
         * into a GROUP's own rollup (a group's status is a plain derived
         * boolean, never itself editable).
         */
        Long latestSanctionId;
        /** That same latest sanction's own reference number — for the status-change confirmation dialog's copy, never looked up separately. */
        String latestSanctionRefNo;
    }

    /**
     * The single source of truth for every Active/Inactive label shown in
     * the Borrower Registry (company, Parent Group, Sub Group) — always
     * "at least one relevant sanction letter is ACTIVE", never a stored
     * field on the company/group itself, never the sanctions COUNT, and
     * never the sanction's own import/review {@code status}.
     */
    private static String deriveStatusLabel(boolean hasActiveSanction) {
        return hasActiveSanction ? "Active" : "Inactive";
    }

    /**
     * A sanction letter defaults to Active — only an explicit {@code
     * INACTIVE} makes it otherwise. Never the other way around: a blank/null
     * value (a row from before this column existed, or one written by
     * something that never set it) must read as Active, not Inactive, same
     * as the entity's own Java-side default for a freshly-constructed row.
     */
    private static boolean isActiveSanction(String activeStatus) {
        return !"INACTIVE".equalsIgnoreCase(activeStatus);
    }

    /**
     * Batched replacement for what used to be one {@code rollupFor(id)} call
     * per borrower — every caller below used to query {@code
     * borrower_sanctions} once per borrower in a loop; this loads every live
     * sanction for the whole id set in a single query and groups them in
     * memory instead. Returns a (possibly empty) Rollup for every id passed
     * in, so a caller can always look up by id without a null check.
     */
    private Map<Long, Rollup> rollupsFor(Collection<Long> borrowerIds) {
        Map<Long, Rollup> out = new LinkedHashMap<>();
        for (Long id : borrowerIds) out.put(id, new Rollup());
        if (borrowerIds.isEmpty()) return out;
        List<Long> ids = borrowerIds instanceof List ? (List<Long>) borrowerIds : new ArrayList<>(borrowerIds);
        for (BorrowerSanctionEntity s
                : sanctionRepo.findByBorrowerIdInAndDeletedAtIsNullOrderBySanctionDateDesc(ids)) {
            Rollup r = out.get(s.getBorrowerId());
            if (r == null) continue; // defensive; every id here came from borrowerIds
            r.sanctionsCount++;
            if (s.getSanctionedAmount() != null) r.total = r.total.add(s.getSanctionedAmount());
            if (isActiveSanction(s.getActiveStatus())) r.hasActiveSanction = true;
            // The query is already ordered by sanctionDate desc across every
            // borrower in the batch, so the first row seen for a given
            // borrower is necessarily that borrower's own most recent one.
            if (r.latestSanctionId == null) {
                r.latestSanctionId = s.getId();
                r.latestSanctionRefNo = s.getRefNo();
            }
        }
        return out;
    }

    private Map<String, Object> toCompanySummary(BorrowerEntity b, Rollup r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("borrowerName", b.getBorrowerName());
        m.put("cin", b.getCin());
        m.put("companyType", companyTypeLabel(b.getIsSubsidiary(), b.getIsSpv()));
        m.put("sanctionsCount", r.sanctionsCount);
        m.put("totalSanctionedAmount", SanctionValueParser.formatCrore(r.total));
        m.put("status", deriveStatusLabel(r.hasActiveSanction));
        // What this row's own Status-column control (see SanctionStatusBadge.js
        // on the frontend) actually edits — the company's own most recent
        // sanction letter, never every sanction it has. Null when the company
        // has none yet, which the frontend renders as a plain read-only pill.
        m.put("latestSanctionId", r.latestSanctionId);
        // Its reference number, for the status-change confirmation dialog's copy.
        m.put("latestSanctionRefNo", r.latestSanctionRefNo);
        return m;
    }

    /**
     * The whole registry as a tree: every top-level Parent Group, its Sub
     * Groups (one level deep) and their companies, plus every standalone
     * company on its own. Built for the registry page's hierarchy view —
     * {@link #getAll} still serves the existing flat sheet unchanged.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getHierarchyTree(Long userId, String userRole) {
        // Resolved once — see the Javadoc on inScope() for why this used to
        // be the single most repeated query on this endpoint.
        int level = roleHierarchyService.getLevelOrder(userRole);

        // Groups are scoped by their own createdBy too — a group the caller
        // can't see hides its whole subtree, including any company under it
        // that the caller WOULD otherwise be able to see on its own merits.
        // That's a deliberate consequence of treating group visibility as a
        // hard boundary rather than something a nested company can see past;
        // if that ever surprises someone about their own company, the fix is
        // moving the company to a group they have visibility into (or an
        // admin reassigning the group's owner).
        List<BorrowerEntity> borrowers = borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .filter(b -> borrowerInScope(b, userId, level))
                .collect(Collectors.toList());
        List<CompanyGroupEntity> groups = companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc().stream()
                .filter(g -> groupInScope(g, userId, level))
                .collect(Collectors.toList());

        // One query for every in-scope borrower's sanctions, instead of one
        // per node while walking the tree below.
        Map<Long, Rollup> rollups = rollupsFor(
                borrowers.stream().map(BorrowerEntity::getId).collect(Collectors.toList()));

        Map<Long, List<BorrowerEntity>> byGroup = new LinkedHashMap<>();
        List<BorrowerEntity> standalone = new ArrayList<>();
        for (BorrowerEntity b : borrowers) {
            if (b.getGroupId() == null) {
                standalone.add(b);
            } else {
                byGroup.computeIfAbsent(b.getGroupId(), k -> new ArrayList<>()).add(b);
            }
        }

        Map<Long, List<CompanyGroupEntity>> subGroupsByParent = new LinkedHashMap<>();
        List<CompanyGroupEntity> topGroups = new ArrayList<>();
        for (CompanyGroupEntity g : groups) {
            if (g.getParentGroupId() == null) {
                topGroups.add(g);
            } else {
                subGroupsByParent.computeIfAbsent(g.getParentGroupId(), k -> new ArrayList<>()).add(g);
            }
        }

        List<Map<String, Object>> groupNodes = new ArrayList<>();
        for (CompanyGroupEntity g : topGroups) {
            Map<String, Object> node = new LinkedHashMap<>();
            buildGroupNode(g, node, byGroup, subGroupsByParent, rollups);
            groupNodes.add(node);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groupNodes);
        out.put("standalone", standalone.stream()
                .map(b -> toCompanySummary(b, rollups.getOrDefault(b.getId(), new Rollup())))
                .collect(Collectors.toList()));
        return out;
    }

    /** Fills {@code outNode} in place and returns this node's own rollup (its companies plus every sub-group's). */
    private Rollup buildGroupNode(CompanyGroupEntity g, Map<String, Object> outNode,
            Map<Long, List<BorrowerEntity>> byGroup, Map<Long, List<CompanyGroupEntity>> subGroupsByParent,
            Map<Long, Rollup> rollups) {
        outNode.put("id", g.getId());
        outNode.put("groupName", g.getGroupName());
        outNode.put("type", g.getParentGroupId() == null ? "GROUP" : "SUB_GROUP");
        // The Group's own optional master CIN — independent of any company's
        // own CIN, never that company's value. See CompanyGroupEntity.cin.
        outNode.put("cin", g.getCin());

        List<BorrowerEntity> companies = byGroup.getOrDefault(g.getId(), List.of());
        List<Map<String, Object>> companyNodes = new ArrayList<>();
        Rollup roll = new Rollup();
        for (BorrowerEntity b : companies) {
            Rollup r = rollups.getOrDefault(b.getId(), new Rollup());
            companyNodes.add(toCompanySummary(b, r));
            roll.sanctionsCount += r.sanctionsCount;
            roll.total = roll.total.add(r.total);
            roll.hasActiveSanction = roll.hasActiveSanction || r.hasActiveSanction;
        }
        outNode.put("companies", companyNodes);

        List<CompanyGroupEntity> subs = subGroupsByParent.getOrDefault(g.getId(), List.of());
        List<Map<String, Object>> subNodes = new ArrayList<>();
        for (CompanyGroupEntity sub : subs) {
            Map<String, Object> subNode = new LinkedHashMap<>();
            Rollup subRoll = buildGroupNode(sub, subNode, byGroup, subGroupsByParent, rollups);
            subNodes.add(subNode);
            roll.sanctionsCount += subRoll.sanctionsCount;
            roll.total = roll.total.add(subRoll.total);
            roll.hasActiveSanction = roll.hasActiveSanction || subRoll.hasActiveSanction;
        }
        outNode.put("subGroups", subNodes);
        outNode.put("sanctionsCount", roll.sanctionsCount);
        outNode.put("totalSanctionedAmount", SanctionValueParser.formatCrore(roll.total));
        outNode.put("status", deriveStatusLabel(roll.hasActiveSanction));
        return roll;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Paginated hierarchy — the registry's Level-1 list, and one group's own
    // Direct Companies / Sub Groups, each fetched a page at a time instead of
    // the whole tree {@link #getHierarchyTree()} still builds in one shot.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Registry-wide totals for the hierarchy view's stat cards — independent
     * of whichever page of the Level-1 list is on screen, so paging never
     * makes "Total Groups"/"Total Companies"/etc. read like a page count.
     * Same walk {@link #getHierarchyTree} always did to arrive at these same
     * figures, just without also building the nested tree around them.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getHierarchyStats(Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<BorrowerEntity> borrowers = borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .filter(b -> borrowerInScope(b, userId, level))
                .collect(Collectors.toList());
        List<CompanyGroupEntity> groups = companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc().stream()
                .filter(g -> groupInScope(g, userId, level))
                .collect(Collectors.toList());
        Map<Long, Rollup> rollups = rollupsFor(
                borrowers.stream().map(BorrowerEntity::getId).collect(Collectors.toList()));
        int sanctionsCount = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Rollup r : rollups.values()) {
            sanctionsCount += r.sanctionsCount;
            total = total.add(r.total);
        }
        // Top-level Parent Groups only — a Sub Group is part of its Parent
        // Group's own hierarchy, not a second top-level entity, so it must
        // not inflate this count (see CompanyGroupEntity.parentGroupId's
        // own doc comment: null means Parent Group, non-null means Sub Group).
        long totalParentGroups = groups.stream().filter(g -> g.getParentGroupId() == null).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalGroups", totalParentGroups);
        stats.put("totalCompanies", borrowers.size());
        stats.put("totalSanctionLetters", sanctionsCount);
        stats.put("totalSanctionedAmount", SanctionValueParser.formatCrore(total));
        return stats;
    }

    /** The lightweight row shape for a Level-1 group — its own hierarchy-wide rollup, no nested companies/subGroups. */
    private Map<String, Object> toGroupSummary(CompanyGroupEntity g, Rollup r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("groupName", g.getGroupName());
        m.put("type", g.getParentGroupId() == null ? "GROUP" : "SUB_GROUP");
        // The Group's own optional master CIN — independent of any company's
        // own CIN, never that company's value. See CompanyGroupEntity.cin.
        m.put("cin", g.getCin());
        m.put("sanctionsCount", r.sanctionsCount);
        m.put("totalSanctionedAmount", SanctionValueParser.formatCrore(r.total));
        m.put("status", deriveStatusLabel(r.hasActiveSanction));
        return m;
    }

    /**
     * One group's own hierarchy-wide rollup — its direct companies plus every
     * one of its Sub Groups' direct companies. For a Sub Group (which can
     * never have Sub Groups of its own) this is simply its direct companies.
     */
    private Rollup rollupForGroupHierarchy(Long groupId, Long userId, int level) {
        List<CompanyGroupEntity> ownGroups = new ArrayList<>();
        companyGroupRepo.findByIdAndDeletedAtIsNull(groupId).ifPresent(ownGroups::add);
        List<CompanyGroupEntity> subs =
                companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(groupId);
        ownGroups.addAll(subs);

        List<BorrowerEntity> candidates = new ArrayList<>(borrowerRepo.findByGroupId(groupId));
        for (CompanyGroupEntity sub : subs) {
            candidates.addAll(borrowerRepo.findByGroupId(sub.getId()));
        }
        List<BorrowerEntity> inScope = candidates.stream()
                .filter(b -> borrowerInScope(b, userId, level))
                .collect(Collectors.toList());
        List<Long> inScopeIds = inScope.stream().map(BorrowerEntity::getId).collect(Collectors.toList());

        Rollup roll = new Rollup();
        for (Rollup r : rollupsFor(inScopeIds).values()) {
            roll.sanctionsCount += r.sanctionsCount;
            roll.total = roll.total.add(r.total);
            roll.hasActiveSanction = roll.hasActiveSanction || r.hasActiveSanction;
        }

        // Sanctions associated directly with this Group or one of its own Sub
        // Groups — folded into the same hierarchy-wide total (so the stat
        // cards don't undercount), but never attributed to any child company;
        // see BorrowerSanctionWrapper.associatedWithType for how a listing
        // keeps that distinction visible. Also counts toward this group's own
        // derived Active/Inactive status, same as any child company's sanction.
        for (CompanyGroupEntity g : ownGroups) {
            if (!groupInScope(g, userId, level)) continue;
            for (BorrowerSanctionEntity s
                    : sanctionRepo.findByGroupIdAndDeletedAtIsNullOrderBySanctionDateDesc(g.getId())) {
                roll.sanctionsCount++;
                if (s.getSanctionedAmount() != null) roll.total = roll.total.add(s.getSanctionedAmount());
                if (isActiveSanction(s.getActiveStatus())) roll.hasActiveSanction = true;
            }
        }
        return roll;
    }

    /**
     * One page of the registry's Level-1 list — top-level Parent Groups then
     * standalone companies, the same order {@link #getHierarchyTree} has
     * always rendered them in, stitched into one virtual sequence so paging
     * reads across both the way the table always has. Groups are few enough
     * to load and filter in memory; standalone companies use a real
     * paginated/search-scoped query since that side can grow much larger.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getHierarchyPage(Long userId, String userRole, int page, int size, String search) {
        String q = SanctionValueParser.isBlank(search) ? null : search.trim().toLowerCase(Locale.ROOT);
        int level = roleHierarchyService.getLevelOrder(userRole);
        // null = unscoped (level <= 2); a concrete id list otherwise — see
        // findStandalonePageScoped/countStandalonePageScoped, which only
        // ever run with a non-null list.
        List<Long> scopeIds = level <= 2 ? null : level == 3 ? resolveTeamMemberIds(userId) : List.of(userId);

        List<CompanyGroupEntity> allTopGroups =
                companyGroupRepo.findByParentGroupIdIsNullAndDeletedAtIsNullOrderByGroupNameAsc().stream()
                        .filter(g -> groupInScope(g, userId, level))
                        .collect(Collectors.toList());
        List<CompanyGroupEntity> matchedGroups = q == null ? allTopGroups
                : allTopGroups.stream()
                        .filter(g -> g.getGroupName() != null && g.getGroupName().toLowerCase(Locale.ROOT).contains(q))
                        .collect(Collectors.toList());

        long totalGroups = matchedGroups.size();
        long totalStandalone = scopeIds == null
                ? borrowerRepo.countStandalonePage(q) : borrowerRepo.countStandalonePageScoped(q, scopeIds);
        long totalElements = totalGroups + totalStandalone;

        int pageStart = page * size;
        int pageEnd = pageStart + size;

        // Groups occupy virtual indices [0, totalGroups); standalone occupy
        // [totalGroups, totalGroups + totalStandalone) — the same order the
        // table has always rendered them in.
        List<Map<String, Object>> groupRows = new ArrayList<>();
        if (pageStart < totalGroups) {
            int from = pageStart;
            int to = (int) Math.min(totalGroups, pageEnd);
            for (CompanyGroupEntity g : matchedGroups.subList(from, to)) {
                groupRows.add(toGroupSummary(g, rollupForGroupHierarchy(g.getId(), userId, level)));
            }
        }

        List<Map<String, Object>> standaloneRows = new ArrayList<>();
        int standaloneFrom = (int) Math.max(0, pageStart - totalGroups);
        int standaloneTo = (int) Math.max(0, Math.min(pageEnd, totalGroups + totalStandalone) - totalGroups);
        int standaloneCount = Math.max(0, standaloneTo - standaloneFrom);
        if (standaloneCount > 0) {
            List<BorrowerEntity> page1 = scopeIds == null
                    ? borrowerRepo.findStandalonePage(q, standaloneFrom, standaloneCount)
                    : borrowerRepo.findStandalonePageScoped(q, scopeIds, standaloneFrom, standaloneCount);
            Map<Long, Rollup> pageRollups = rollupsFor(
                    page1.stream().map(BorrowerEntity::getId).collect(Collectors.toList()));
            for (BorrowerEntity b : page1) {
                standaloneRows.add(toCompanySummary(b, pageRollups.getOrDefault(b.getId(), new Rollup())));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groupRows);
        out.put("standalone", standaloneRows);
        out.put("page", page);
        out.put("pageSize", size);
        out.put("totalElements", totalElements);
        out.put("totalPages", size > 0 ? (int) Math.ceil((double) totalElements / size) : 0);
        out.put("stats", getHierarchyStats(userId, userRole));
        return out;
    }

    /** null for level <= 2 (unscoped), else the exact set of createdBy ids this caller may see. */
    private List<Long> scopeIdsFor(Long userId, int level) {
        return level <= 2 ? null : level == 3 ? resolveTeamMemberIds(userId) : List.of(userId);
    }

    /** One Parent Group or Sub Group's own summary — breadcrumb + stat-card figures, no nested rows. */
    @Transactional(readOnly = true)
    public CompanyGroupWrapper getGroupDetail(Long userId, String userRole, Long id) throws CustomException {
        int level = roleHierarchyService.getLevelOrder(userRole);
        CompanyGroupEntity g = companyGroupRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Group not found"));
        if (!groupInScope(g, userId, level)) {
            throw new CustomException("Group not found");
        }
        CompanyGroupWrapper w = toGroupWrapper(g);
        List<Long> scopeIds = scopeIdsFor(userId, level);

        int subGroupsCount = (int) companyGroupRepo
                .findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(id).stream()
                .filter(sub -> groupInScope(sub, userId, level))
                .count();
        w.setHasSubGroups(subGroupsCount > 0);
        w.setSubGroupsCount(subGroupsCount);
        w.setDirectCompaniesCount(scopeIds == null
                ? (int) borrowerRepo.countByGroupIdAndDeletedAtIsNull(id)
                : (int) borrowerRepo.countByGroupIdAndCreatedByInAndDeletedAtIsNull(id, scopeIds));
        w.setTotalCompaniesCount(scopeIds == null
                ? (int) borrowerRepo.countCompaniesUnderGroup(id)
                : (int) borrowerRepo.countCompaniesUnderGroupScoped(id, scopeIds));
        w.setTotalSpvCount(scopeIds == null
                ? (int) borrowerRepo.countSpvsUnderGroup(id)
                : (int) borrowerRepo.countSpvsUnderGroupScoped(id, scopeIds));

        Rollup roll = rollupForGroupHierarchy(id, userId, level);
        w.setSanctionsCount(roll.sanctionsCount);
        w.setTotalSanctionedAmount(SanctionValueParser.formatCrore(roll.total));
        w.setStatus(deriveStatusLabel(roll.hasActiveSanction));
        return w;
    }

    /** One page of the companies sitting directly under a group — Direct Companies, or one Sub Group's own table. */
    @Transactional(readOnly = true)
    public PagedResponseWrapper<Map<String, Object>> getGroupCompanies(
            Long userId, String userRole, Long groupId, int page, int size) {
        List<Long> scopeIds = scopeIdsFor(userId, roleHierarchyService.getLevelOrder(userRole));
        Page<BorrowerEntity> p = scopeIds == null
                ? borrowerRepo.findByGroupIdAndDeletedAtIsNullOrderByBorrowerNameAsc(groupId, PageRequest.of(page, size))
                : borrowerRepo.findByGroupIdAndCreatedByInAndDeletedAtIsNullOrderByBorrowerNameAsc(
                        groupId, scopeIds, PageRequest.of(page, size));
        Map<Long, Rollup> rollups = rollupsFor(
                p.getContent().stream().map(BorrowerEntity::getId).collect(Collectors.toList()));
        List<Map<String, Object>> content = p.getContent().stream()
                .map(b -> toCompanySummary(b, rollups.getOrDefault(b.getId(), new Rollup())))
                .collect(Collectors.toList());
        return PagedResponseWrapper.of(content, page, size, p.getTotalElements());
    }

    /** One page of the Sub Groups sitting directly under a Parent Group, each with its own summary (not its companies). */
    @Transactional(readOnly = true)
    public PagedResponseWrapper<CompanyGroupWrapper> getSubGroups(
            Long userId, String userRole, Long groupId, int page, int size) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<Long> scopeIds = scopeIdsFor(userId, level);
        Page<CompanyGroupEntity> p = scopeIds == null
                ? companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(
                        groupId, PageRequest.of(page, size))
                : companyGroupRepo.findByParentGroupIdAndCreatedByInAndDeletedAtIsNullOrderByGroupNameAsc(
                        groupId, scopeIds, PageRequest.of(page, size));
        List<CompanyGroupWrapper> content = p.getContent().stream().map(sub -> {
            CompanyGroupWrapper w = toGroupWrapper(sub);
            // A Sub Group can never have Sub Groups of its own, so this rollup
            // is simply its own direct companies.
            Rollup r = rollupForGroupHierarchy(sub.getId(), userId, level);
            w.setCompaniesCount(scopeIds == null
                    ? (int) borrowerRepo.countByGroupIdAndDeletedAtIsNull(sub.getId())
                    : (int) borrowerRepo.countByGroupIdAndCreatedByInAndDeletedAtIsNull(sub.getId(), scopeIds));
            w.setSanctionsCount(r.sanctionsCount);
            w.setTotalSanctionedAmount(SanctionValueParser.formatCrore(r.total));
            w.setStatus(deriveStatusLabel(r.hasActiveSanction));
            return w;
        }).collect(Collectors.toList());
        return PagedResponseWrapper.of(content, page, size, p.getTotalElements());
    }

    /**
     * Soft duplicate check for the import flow (registry ref. no. is already
     * a hard block elsewhere — see {@link #withMeta}). Same lender and same
     * sanction date on the same company is very likely the same letter
     * re-imported under a different ref. no. typo; it is only ever shown as a
     * warning, never blocked, since an amended or restated sanction is a
     * legitimate second letter with the same date and lender.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> checkDuplicateSanction(Long borrowerId, String lenderName, String sanctionDate) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (borrowerId == null || SanctionValueParser.isBlank(lenderName)) return out;
        LocalDate date = SanctionValueParser.parseDate(sanctionDate);
        if (date == null) return out;
        String lender = lenderName.trim();
        for (BorrowerSanctionEntity s :
                sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(borrowerId)) {
            if (date.equals(s.getSanctionDate()) && lender.equalsIgnoreCase(
                    String.valueOf(s.getLenderName() == null ? "" : s.getLenderName()).trim())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("refNo", s.getRefNo());
                m.put("sanctionDate", SanctionValueParser.formatDate(s.getSanctionDate()));
                m.put("lenderName", s.getLenderName());
                m.put("sanctionedAmount", SanctionValueParser.formatCrore(s.getSanctionedAmount()));
                out.add(m);
            }
        }
        return out;
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
    public BorrowerWrapper saveSanction(BorrowerSanctionWrapper in, String identityCin,
                                        String identityRegisteredAddress, Long userId, String userRole,
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

        // A reviewer's correction to a misread CIN/registered address, made
        // on the same "Review what was read" screen — validated and applied
        // here, in the SAME transaction as the sanction save (previously a
        // separate PUT /borrower/{id}/identity call), so a bad value or any
        // later failure in this method can never leave the borrower updated
        // with no sanction to show for it. See the save-flow atomicity fix,
        // 2026-09-02 — this used to be able to commit on its own and then
        // have the sanction save fail afterward, leaving an orphaned/
        // wrongly-updated borrower behind.
        boolean identityChanged = false;
        if (!SanctionValueParser.isBlank(identityCin)) {
            String validCin = SanctionValueParser.requireValidCin(identityCin);
            assertCinFree(validCin, borrower.getId());
            borrower.setCin(validCin);
            identityChanged = true;
        }
        if (!SanctionValueParser.isBlank(identityRegisteredAddress)) {
            borrower.setRegisteredAddress(SanctionValueParser.clean(identityRegisteredAddress));
            identityChanged = true;
        }
        if (identityChanged) {
            borrower.setUpdatedBy(userId);
            borrowerRepo.save(borrower);
        }

        BorrowerSanctionEntity e = in.getId() == null
                ? new BorrowerSanctionEntity()
                : sanctionRepo.findByIdAndDeletedAtIsNull(in.getId())
                        .orElseThrow(() -> new CustomException("Sanction not found"));

        boolean isNew = e.getId() == null;
        // A brand-new letter is only ever attached here after the always-
        // global Company Match flow — deliberately not scope-checked, or a
        // rank-3/4 user could never attach a sanction to an existing company
        // matched from outside their own scope (the whole reason matching
        // stays unscoped — see matchBorrower). Editing an already-saved
        // sanction is a different action on a record already in the
        // system, so that IS scope-checked — against the owning borrower OR
        // this sanction's own createdBy, so the person who attached it can
        // still correct it even when the borrower itself belongs to someone
        // else (see assertSanctionWriteScope).
        if (!isNew) {
            assertSanctionWriteScope(userId, userRole, borrower.getCreatedBy(), e);
        }
        applySanction(e, in);
        e.setBorrowerId(borrower.getId());
        e.setGroupId(null);

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
        return buildBorrowerWrapper(borrower.getId());
    }

    /**
     * Save a sanction letter associated directly with a Parent Group or Sub
     * Group — never with any company underneath it. Mirrors {@link
     * #saveSanction}'s ref-no duplicate check and {@link #validateSanction}
     * business rules, but never touches a borrower row: a sanction has
     * exactly one of {@code borrowerId}/{@code groupId} set (see {@code
     * BorrowerSanctionEntity}), and this path always sets {@code groupId}.
     */
    @Transactional
    public BorrowerSanctionWrapper saveGroupSanction(BorrowerSanctionWrapper in, Long groupId, Long userId,
                                                      String userRole, String rawExtractedJson) throws CustomException {
        if (SanctionValueParser.isBlank(in.getRefNo())) {
            throw new CustomException("Reference number is required");
        }
        Optional<BorrowerSanctionEntity> dup =
                sanctionRepo.findByRefNoIgnoreCaseAndDeletedAtIsNull(in.getRefNo().trim());
        if (dup.isPresent() && !dup.get().getId().equals(in.getId())) {
            throw new CustomException(
                    "Sanction letter " + in.getRefNo() + " has already been imported.");
        }
        CompanyGroupEntity group = companyGroupRepo.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new CustomException("Group not found"));

        BorrowerSanctionEntity e = in.getId() == null
                ? new BorrowerSanctionEntity()
                : sanctionRepo.findByIdAndDeletedAtIsNull(in.getId())
                        .orElseThrow(() -> new CustomException("Sanction not found"));
        boolean isNew = e.getId() == null;
        if (!isNew) {
            assertSanctionWriteScope(userId, userRole, group.getCreatedBy(), e);
        }
        applySanction(e, in);
        e.setGroupId(group.getId());
        e.setBorrowerId(null);

        if (isNew) {
            e.setCreatedBy(userId);
            if (rawExtractedJson != null) {
                e.setRawExtractedJson(rawExtractedJson);
            }
        } else {
            e.setUpdatedBy(userId);
            if ("IMPORTED".equals(e.getSource())) {
                e.setSource("IMPORTED_EDITED");
            }
        }

        validateSanction(e);
        sanctionRepo.save(e);
        return toWrapper(e, null);
    }

    /** Every sanction associated directly with one Parent Group or Sub Group — never a child company's own. */
    @Transactional(readOnly = true)
    public List<BorrowerSanctionWrapper> listGroupSanctions(Long userId, String userRole, Long groupId)
            throws CustomException {
        int level = roleHierarchyService.getLevelOrder(userRole);
        CompanyGroupEntity group = companyGroupRepo.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new CustomException("Group not found"));
        if (!groupInScope(group, userId, level)) {
            throw new CustomException("Group not found");
        }
        return sanctionRepo.findByGroupIdAndDeletedAtIsNullOrderBySanctionDateDesc(groupId).stream()
                .map(s -> toWrapper(s, null))
                .collect(Collectors.toList());
    }

    /**
     * Find an existing borrower by name, or create one. Called by the review
     * screen once the user has confirmed which company the letter belongs to.
     *
     * <p>The letter also carries borrower-level values the sanction row has no
     * home for — promoter, guarantor, group, Cat / Sub Cat. Those are filled in
     * here, but only where the borrower is still blank: an import must never
     * overwrite something a user typed.
     *
     * <p>Deliberately unscoped by Role Hierarchy, same reasoning as
     * {@link #matchBorrower} — this is what actually attaches to whichever
     * company the (always cross-team) match screen resolved to.
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
            if (!fillBlanks(b, in)) return buildBorrowerWrapper(b.getId());
            b.setUpdatedBy(userId);
        } else {
            // Defence in depth — the sanction-import UI never reaches this
            // branch for a genuinely new company (it goes through
            // resolveBorrowerWithHierarchy instead), but this endpoint is a
            // public API any client could call directly.
            assertNameNotUsedByGroup(name);
            b = new BorrowerEntity();
            b.setBorrowerName(name);
            fillBlanks(b, in);
            b.setCreatedBy(userId);
        }
        return buildBorrowerWrapper(borrowerRepo.save(b).getId());
    }

    /**
     * Atomic combination of {@link #resolveBorrower} + {@link
     * #updateBorrowerHierarchy}, for the sanction-import "confirm this
     * company" step (CompanyMatchModal's "new company" / group-suggestion
     * options) — resolving the borrower and placing it in its chosen
     * Parent/Sub Group must either both happen or neither happen. Two
     * separate calls (the previous behaviour) could resolve/create the
     * borrower successfully and then fail to set its hierarchy, leaving a
     * borrower committed with no group and no sanction yet to show for it.
     *
     * <p>Deliberately not just a "hierarchy" parameter bolted onto {@link
     * #resolveBorrower} itself — that method has one other caller
     * (plain {@code /borrower/resolve}, used for the "this might be the
     * same existing company" match, which never changes hierarchy) that
     * must keep behaving exactly as it does today.
     *
     * <p>Also creates the Parent/Sub Group itself, if the caller asked for a
     * new one, in this SAME transaction — group creation used to be a
     * separate {@code POST /borrower/groups} call made by the frontend
     * before this one, so a failure here (or in this method's own
     * duplicate-name check, below) could leave a newly-created group
     * committed with no company or sanction ever attached to it. An
     * already-existing group passed in as {@code parentGroupId}/{@code
     * subGroupId} is never touched by that risk — it's simply looked up.
     *
     * <p>Also enforces the one-namespace rule between company/borrower names
     * and group names (an exact, normalised match — {@link
     * CompanyNameMatcher#normalizedEquals}, never the fuzzy {@code
     * similarity()} scorer): a brand-new group cannot be created under a
     * name an existing company already uses, and a brand-new company cannot
     * be created under a name an existing group already uses. This runs
     * before either row is written, so no partial creation is possible.
     */
    @Transactional
    public BorrowerWrapper resolveBorrowerWithHierarchy(BorrowerWrapper in,
            Long parentGroupId, String newParentGroupName,
            Long subGroupId, String newSubGroupName,
            Boolean isSubsidiary, Boolean isSpv, Long userId) throws CustomException {
        return resolveBorrowerWithHierarchy(in, parentGroupId, newParentGroupName, null, null,
                subGroupId, newSubGroupName, null, null, isSubsidiary, isSpv, userId);
    }

    /**
     * Same as the 8-arg form, additionally accepting the new Parent/Sub
     * Group's own optional master CIN/registered address (ignored when the
     * corresponding group id is already given, since an existing group's
     * master details are never touched by this "confirm this company" flow —
     * see {@link #updateGroup} for editing a group's own fields).
     */
    @Transactional
    public BorrowerWrapper resolveBorrowerWithHierarchy(BorrowerWrapper in,
            Long parentGroupId, String newParentGroupName, String newParentGroupCin, String newParentGroupAddress,
            Long subGroupId, String newSubGroupName, String newSubGroupCin, String newSubGroupAddress,
            Boolean isSubsidiary, Boolean isSpv, Long userId) throws CustomException {
        if (in == null || SanctionValueParser.isBlank(in.getBorrowerName())) {
            throw new CustomException("Borrower name is required");
        }
        String name = in.getBorrowerName().trim();

        Long resolvedGroupId = parentGroupId;
        if (resolvedGroupId == null && !SanctionValueParser.isBlank(newParentGroupName)) {
            resolvedGroupId = createGroupChecked(newParentGroupName.trim(), null,
                    newParentGroupCin, newParentGroupAddress, userId);
        } else {
            assertGroupValid(resolvedGroupId);
        }
        // A Sub Group can never exist without a Parent Group — reject rather
        // than silently drop the Sub Group choice, so a caller bug (or a
        // request sent outside the UI) can't leave the user thinking a
        // company landed under a Sub Group when it actually didn't.
        if (resolvedGroupId == null
                && (subGroupId != null || !SanctionValueParser.isBlank(newSubGroupName))) {
            throw new CustomException("A Sub Group requires a Parent Group.");
        }
        if (resolvedGroupId != null) {
            if (subGroupId != null) {
                assertGroupValid(subGroupId);
                resolvedGroupId = subGroupId;
            } else if (!SanctionValueParser.isBlank(newSubGroupName)) {
                resolvedGroupId = createGroupChecked(newSubGroupName.trim(), resolvedGroupId,
                        newSubGroupCin, newSubGroupAddress, userId);
            }
        }

        assertNameNotUsedByGroup(name);
        Optional<BorrowerEntity> existing =
                borrowerRepo.findByBorrowerNameIgnoreCaseAndDeletedAtIsNull(name);

        BorrowerEntity b;
        if (existing.isPresent()) {
            b = existing.get();
            fillBlanks(b, in);
            b.setUpdatedBy(userId);
        } else {
            b = new BorrowerEntity();
            b.setBorrowerName(name);
            fillBlanks(b, in);
            b.setCreatedBy(userId);
        }
        if (resolvedGroupId != null) b.setGroupId(resolvedGroupId);
        if (isSubsidiary != null) b.setIsSubsidiary(isSubsidiary);
        if (isSpv != null) b.setIsSpv(isSpv);
        return buildBorrowerWrapper(borrowerRepo.save(b).getId());
    }

    /**
     * Creates a Parent Group ({@code parentId == null}) or Sub Group, after
     * checking for an exact-name collision against both other groups (the
     * pre-existing check) and existing companies/borrowers (new — see the
     * class comment on {@link #resolveBorrowerWithHierarchy}). Shared by
     * that method and the standalone {@code POST /borrower/groups} endpoint
     * ({@link #createGroup}) so the same rule applies everywhere a group can
     * be created, not just from the sanction-import flow.
     */
    private Long createGroupChecked(String name, Long parentId, Long userId) throws CustomException {
        return createGroupChecked(name, parentId, null, null, userId);
    }

    /** Same as the 3-arg form, additionally setting the Group's own optional master CIN/registered address. */
    private Long createGroupChecked(String name, Long parentId, String cin, String registeredAddress, Long userId)
            throws CustomException {
        String kind = parentId == null ? "Parent Group" : "Sub Group";
        if (parentId != null) {
            CompanyGroupEntity parent = companyGroupRepo.findByIdAndDeletedAtIsNull(parentId)
                    .orElseThrow(() -> new CustomException("Parent group not found"));
            if (parent.getParentGroupId() != null) {
                throw new CustomException("A Sub Group cannot itself be nested under another Sub Group.");
            }
        }
        if (companyGroupRepo.findByExactNameAndParent(name, parentId).isPresent()) {
            throw new CustomException(parentId == null
                    ? "A Parent Group named \"" + name + "\" already exists."
                    : "A Sub Group named \"" + name + "\" already exists under that Parent Group.");
        }
        assertNameNotUsedByBorrower(name, kind);
        String validCin = SanctionValueParser.requireValidCin(cin);
        assertGroupCinFree(validCin, null);
        CompanyGroupEntity g = new CompanyGroupEntity();
        g.setGroupName(name);
        g.setParentGroupId(parentId);
        g.setCin(validCin);
        g.setRegisteredAddress(SanctionValueParser.isBlank(registeredAddress)
                ? null : SanctionValueParser.clean(registeredAddress));
        g.setCreatedBy(userId);
        return companyGroupRepo.save(g).getId();
    }

    /**
     * A Group's optional master CIN must not collide with another Group's, nor
     * with any company's own {@code borrowers.cin} — the same "one CIN, one
     * legal entity" rule as {@link #assertCinFree}, just checked against both
     * tables since a CIN could otherwise land on a Group here and a Company
     * there for the very same real-world entity.
     */
    private void assertGroupCinFree(String cin, Long selfGroupId) throws CustomException {
        if (SanctionValueParser.isBlank(cin)) return;
        for (CompanyGroupEntity g : companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc()) {
            if (cin.equalsIgnoreCase(g.getCin()) && (selfGroupId == null || !g.getId().equals(selfGroupId))) {
                throw new CustomException("That CIN already belongs to the group \"" + g.getGroupName() + "\"");
            }
        }
        Optional<BorrowerEntity> other = borrowerRepo.findByCinIgnoreCaseAndDeletedAtIsNull(cin);
        if (other.isPresent()) {
            throw new CustomException("That CIN already belongs to " + other.get().getBorrowerName());
        }
    }

    /** Part of the group/company one-namespace rule — see {@link #resolveBorrowerWithHierarchy}. */
    private void assertNameNotUsedByBorrower(String candidateGroupName, String kindLabel) throws CustomException {
        for (BorrowerEntity b : borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc()) {
            if (CompanyNameMatcher.normalizedEquals(candidateGroupName, b.getBorrowerName())) {
                throw new CustomException("An existing company already uses this name. A " + kindLabel
                        + " with the same name cannot be created.");
            }
        }
    }

    /** Part of the group/company one-namespace rule — see {@link #resolveBorrowerWithHierarchy}. */
    private void assertNameNotUsedByGroup(String candidateBorrowerName) throws CustomException {
        for (CompanyGroupEntity g : companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc()) {
            if (CompanyNameMatcher.normalizedEquals(candidateBorrowerName, g.getGroupName())) {
                throw new CustomException("An existing Parent/Sub Group already uses this name (\""
                        + g.getGroupName() + "\"). Choose a different company name, or select that group "
                        + "in the hierarchy instead of typing a new company here.");
            }
        }
    }

    /** Copy the parsed borrower-level values onto blank fields only. */
    private boolean fillBlanks(BorrowerEntity b, BorrowerWrapper in) {
        boolean changed = false;
        // CIN is the natural key, so only fill it in when it won't collide
        // with a CIN already on file for a different borrower. Uses the
        // lenient normalizer, not requireValidCin — this runs off whatever
        // the deterministic extractor scraped off a letter, and a single
        // mis-scanned identity field must never fail the whole import; an
        // unusable value here is simply left blank, same as if nothing had
        // been extracted at all.
        if (SanctionValueParser.isBlank(b.getCin()) && !SanctionValueParser.isBlank(in.getCin())) {
            String cin = SanctionValueParser.normalizeCinOrNull(in.getCin());
            if (cin != null) {
                Optional<BorrowerEntity> other = borrowerRepo.findByCinIgnoreCaseAndDeletedAtIsNull(cin);
                if (other.isEmpty() || other.get().getId().equals(b.getId())) {
                    b.setCin(cin);
                    changed = true;
                }
            }
        }
        if (SanctionValueParser.isBlank(b.getRegisteredAddress())
                && !SanctionValueParser.isBlank(in.getRegisteredAddress())) {
            b.setRegisteredAddress(SanctionValueParser.clean(in.getRegisteredAddress()));
            changed = true;
        }
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
    public void deleteSanction(Long id, Long userId, String userRole) throws CustomException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionWriteScope(userId, userRole, ownerCreatedByFor(s), s);
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
        if (e.getSanctionDate() != null && e.getActualCod() != null
                && e.getActualCod().isBefore(e.getSanctionDate())) {
            throw new CustomException("The actual COD cannot fall before the sanction date.");
        }
        if (e.getDisbursementDate() == null) {
            throw new CustomException("Disbursement date is required.");
        }
        if (e.getSanctionDate() != null) {
            LocalDate validTill = derived.sanctionValidTill(e.getSanctionDate());
            if (e.getDisbursementDate().isBefore(e.getSanctionDate())
                    || e.getDisbursementDate().isAfter(validTill)) {
                throw new CustomException("The disbursement date must fall between the sanction date and "
                        + "the date the sanction lapses (" + SanctionValueParser.formatDate(validTill) + ").");
            }
        }
        if (e.getRepaymentStartDate() != null && e.getRepaymentEndDate() != null
                && e.getRepaymentEndDate().isBefore(e.getRepaymentStartDate())) {
            throw new CustomException(
                    "The repayment end date cannot fall before the repayment start date.");
        }

        // ── repayment-schedule inputs: everything the reserve/schedule math
        //    assumes stays valid. Once these hold, LoanReserveCalculator's own
        //    algorithm already guarantees no negative principal/interest, no
        //    principal instalment above the outstanding balance, and closing
        //    to exactly zero — there is nothing further to check for those. ──
        if (e.getSanctionedAmount() != null && e.getSanctionedAmount().signum() <= 0) {
            throw new CustomException("The sanctioned amount must be greater than zero.");
        }
        if (e.getRoiPct() != null && e.getRoiPct().signum() < 0) {
            throw new CustomException("The rate of interest cannot be negative.");
        }
        if (e.getBaseRatePct() != null && e.getBaseRatePct().signum() < 0) {
            throw new CustomException("The base rate cannot be negative.");
        }
        if (e.getSpreadPct() != null && e.getSpreadPct().signum() < 0) {
            throw new CustomException("The spread cannot be negative.");
        }
        if (e.getTenorMonths() != null && e.getTenorMonths() <= 0) {
            throw new CustomException("The tenor must be greater than zero months.");
        }
        if (e.getMoratoriumMonths() != null && e.getMoratoriumMonths() < 0) {
            throw new CustomException("The moratorium cannot be negative.");
        }
        if (e.getTenorMonths() != null && e.getMoratoriumMonths() != null
                && e.getMoratoriumMonths() >= e.getTenorMonths()) {
            throw new CustomException("The moratorium cannot be equal to or longer than the total tenor.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // The letter itself
    // ════════════════════════════════════════════════════════════════════════

    /**
     * The one editable Active/Inactive control in the Borrower Registry —
     * changes ONLY this sanction letter's own {@code activeStatus}. Never
     * touches the parent Company/Parent Group/Sub Group, never any other
     * sanction: every displayed Company/Group status is derived fresh from
     * sanctions at read time (see {@link #deriveStatusLabel}), so nothing
     * else needs updating here.
     */
    @Transactional
    public BorrowerSanctionWrapper updateSanctionStatus(Long sanctionId, String activeStatus,
            Long userId, String userRole) throws CustomException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionWriteScope(userId, userRole, ownerCreatedByFor(s), s);
        String v = SanctionValueParser.clean(activeStatus);
        v = v == null ? null : v.toUpperCase(Locale.ENGLISH);
        if (!"ACTIVE".equals(v) && !"INACTIVE".equals(v)) {
            throw new CustomException("Status must be Active or Inactive");
        }
        s.setActiveStatus(v);
        s.setUpdatedBy(userId);
        BorrowerEntity owner = s.getBorrowerId() == null ? null
                : borrowerRepo.findByIdAndDeletedAtIsNull(s.getBorrowerId()).orElse(null);
        return toWrapper(sanctionRepo.save(s), owner);
    }

    @Transactional
    public BorrowerSanctionWrapper uploadDocument(Long sanctionId, MultipartFile file, Long userId, String userRole)
            throws CustomException {
        validateFile(file);
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionWriteScope(userId, userRole, ownerCreatedByFor(s), s);
        try {
            s.setSanctionDocData(file.getBytes());
        } catch (Exception e) {
            throw new CustomException("Could not store the file: " + e.getMessage());
        }
        s.setSanctionDocName(file.getOriginalFilename());
        s.setSanctionDocMime(file.getContentType());
        s.setSanctionDocSize(file.getSize());
        s.setUpdatedBy(userId);
        BorrowerEntity owner = s.getBorrowerId() == null ? null
                : borrowerRepo.findByIdAndDeletedAtIsNull(s.getBorrowerId()).orElse(null);
        return toWrapper(sanctionRepo.save(s), owner);
    }

    /**
     * Build the payload the in-page viewer needs.
     *
     * <p>Runs inside a transaction and pulls the bytes by explicit query rather
     * than off a detached entity, which is what made the first version fail.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildPreview(Long userId, String userRole, Long sanctionId)
            throws CustomException, IOException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionReadScope(userId, userRole, s);

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
    public BorrowerSanctionEntity getDocumentEntity(Long userId, String userRole, Long sanctionId)
            throws CustomException {
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionReadScope(userId, userRole, s);
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

    private void applyBorrower(BorrowerEntity b, BorrowerWrapper in) throws CustomException {
        b.setBorrowerName(SanctionValueParser.clean(in.getBorrowerName()));
        // Callers (createBorrower/updateBorrower) already normalize in.getCin()
        // before this runs, so this is normally a no-op re-check — kept here
        // too so this method stays correct on its own, not just by caller
        // discipline.
        b.setCin(SanctionValueParser.requireValidCin(in.getCin()));
        b.setPan(SanctionValueParser.clean(in.getPan()));
        b.setSponsorName(SanctionValueParser.clean(in.getSponsorName()));
        b.setPromoterName(SanctionValueParser.clean(in.getPromoterName()));
        b.setGuarantorName(SanctionValueParser.clean(in.getGuarantorName()));
        b.setGroupName(SanctionValueParser.clean(in.getGroupName()));
        b.setGroupId(in.getGroupId());
        b.setIsSubsidiary(in.getIsSubsidiary() != null && in.getIsSubsidiary());
        b.setIsSpv(in.getIsSpv() != null && in.getIsSpv());
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
        e.setInstrument(SanctionValueParser.clean(in.getInstrument()));

        e.setCoObligators(SanctionValueParser.clean(in.getCoObligators()));
        e.setPledgeOfSharesPct(SanctionValueParser.parsePct(in.getPledgeOfSharesPct()));

        e.setMinDscr(SanctionValueParser.parseMultiple(in.getMinDscr()));
        e.setAvgDscr(SanctionValueParser.parseMultiple(in.getAvgDscr()));
        e.setDsra(SanctionValueParser.clean(in.getDsra()));
        e.setIsra(SanctionValueParser.clean(in.getIsra()));
        // Same crore convention as the other money fields on this form. A
        // blank box clears the manual override back to null, so the next
        // read simply resumes following the calculated figure — never a
        // "leave as-is" guard like status/source below.
        e.setDsraAmount(SanctionValueParser.parseMoneyCrore(in.getDsraAmount()));
        e.setIsraAmount(SanctionValueParser.parseMoneyCrore(in.getIsraAmount()));
        e.setCashSweep(SanctionValueParser.clean(in.getCashSweep()));

        e.setTenorText(SanctionValueParser.clean(in.getTenorText()));
        e.setTenorMonths(in.getTenorMonths() != null
                ? SanctionValueParser.parseInt(in.getTenorMonths())
                : SanctionValueParser.parseTenorMonths(in.getTenorText()));
        e.setMoratoriumMonths(in.getMoratoriumMonths() != null
                ? SanctionValueParser.parseInt(in.getMoratoriumMonths())
                : SanctionValueParser.parseMoratoriumMonths(in.getTenorText()));
        // Blank/omitted means "leave the current value (or the entity's
        // default of SERVICED for a new row) as-is" — same convention as
        // status/source below, not a fresh guess every save.
        if (!SanctionValueParser.isBlank(in.getInterestDuringMoratorium())) {
            e.setInterestDuringMoratorium(
                    SanctionValueParser.clean(in.getInterestDuringMoratorium()).toUpperCase(Locale.ENGLISH));
        }
        // Same "blank means leave as-is (or the entity's default of
        // QUARTERLY for a new row)" convention as interestDuringMoratorium
        // above — never a fresh guess every save.
        if (!SanctionValueParser.isBlank(in.getRepaymentFrequency())) {
            e.setRepaymentFrequency(
                    SanctionValueParser.clean(in.getRepaymentFrequency()).toUpperCase(Locale.ENGLISH));
        }
        e.setRepaymentFrequencyOtherMonths(SanctionValueParser.parseInt(in.getRepaymentFrequencyOtherMonths()));
        // Whatever the reviewer's table produced, verbatim — a blank/absent
        // value means "no override yet", resuming the auto equal-split, same
        // as clearing dsraAmount/israAmount back to their calculated figure.
        e.setRepaymentProfileJson(SanctionValueParser.isBlank(in.getRepaymentProfileJson())
                ? null : in.getRepaymentProfileJson());

        e.setDisbursementDate(SanctionValueParser.parseDate(in.getDisbursementDate()));
        e.setRepaymentStartDate(SanctionValueParser.parseDate(in.getRepaymentStartDate()));
        e.setRepaymentEndDate(SanctionValueParser.parseDate(in.getRepaymentEndDate()));
        e.setScheduledCod(SanctionValueParser.parseDate(in.getScheduledCod()));
        e.setActualCod(SanctionValueParser.parseDate(in.getActualCod()));

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
        w.setGroupId(b.getGroupId());
        w.setIsSubsidiary(Boolean.TRUE.equals(b.getIsSubsidiary()));
        w.setIsSpv(Boolean.TRUE.equals(b.getIsSpv()));
        w.setCompanyType(companyTypeLabel(b.getIsSubsidiary(), b.getIsSpv()));
        // Own status is set once its sanctions are known — see buildBorrowerWrapper.
        GroupPath gp = resolveGroupPath(b.getGroupId());
        w.setParentGroupId(gp.parentGroupId);
        w.setParentGroupName(gp.parentGroupName);
        w.setSubGroupId(gp.subGroupId);
        w.setSubGroupName(gp.subGroupName);
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

    /**
     * @param parent the borrower this sanction belongs to — already loaded by
     *               every caller (they got {@code e} by querying off its id
     *               in the first place), so this is a plain in-memory read,
     *               not an extra query. Supplies {@code cin} for display; see
     *               the field's own Javadoc on {@link BorrowerSanctionWrapper}.
     */
    private BorrowerSanctionWrapper toWrapper(BorrowerSanctionEntity e, BorrowerEntity parent) {
        BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
        w.setId(e.getId());
        w.setBorrowerId(e.getBorrowerId());
        w.setGroupId(e.getGroupId());
        w.setRefNo(e.getRefNo());
        w.setSanctionDate(SanctionValueParser.formatDate(e.getSanctionDate()));
        w.setLenderName(e.getLenderName());
        w.setProjectName(e.getProjectName());
        w.setCategory(e.getCategory());
        w.setLocation(e.getLocation());
        if (parent != null) {
            w.setCin(parent.getCin());
            w.setRegisteredAddress(parent.getRegisteredAddress());
            w.setAssociatedWithType("COMPANY");
            w.setAssociatedWithName(parent.getBorrowerName());
        } else if (e.getGroupId() != null) {
            CompanyGroupEntity group = companyGroupRepo.findByIdAndDeletedAtIsNull(e.getGroupId()).orElse(null);
            if (group != null) {
                w.setCin(group.getCin());
                w.setRegisteredAddress(group.getRegisteredAddress());
                w.setAssociatedWithType(group.getParentGroupId() == null ? "GROUP" : "SUB_GROUP");
                w.setAssociatedWithName(group.getGroupName());
            }
        }
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
        w.setInstrument(e.getInstrument());

        w.setCoObligators(e.getCoObligators());
        w.setPledgeOfSharesPct(SanctionValueParser.formatPct(e.getPledgeOfSharesPct()));

        w.setMinDscr(SanctionValueParser.formatMultiple(e.getMinDscr()));
        w.setAvgDscr(SanctionValueParser.formatMultiple(e.getAvgDscr()));
        w.setDsra(e.getDsra());
        w.setIsra(e.getIsra());
        w.setDsraAmount(SanctionValueParser.formatCrore(e.getDsraAmount()));
        w.setIsraAmount(SanctionValueParser.formatCrore(e.getIsraAmount()));
        w.setCashSweep(e.getCashSweep());

        w.setTenorText(e.getTenorText());
        w.setTenorMonths(SanctionValueParser.str(e.getTenorMonths()));
        w.setMoratoriumMonths(SanctionValueParser.str(e.getMoratoriumMonths()));
        w.setInterestDuringMoratorium(e.getInterestDuringMoratorium());
        w.setRepaymentFrequency(e.getRepaymentFrequency());
        w.setRepaymentFrequencyOtherMonths(SanctionValueParser.str(e.getRepaymentFrequencyOtherMonths()));
        w.setRepaymentProfileJson(e.getRepaymentProfileJson());

        w.setDisbursementDate(SanctionValueParser.formatDate(e.getDisbursementDate()));
        w.setRepaymentStartDate(SanctionValueParser.formatDate(e.getRepaymentStartDate()));
        w.setRepaymentEndDate(SanctionValueParser.formatDate(e.getRepaymentEndDate()));
        w.setScheduledCod(SanctionValueParser.formatDate(e.getScheduledCod()));
        w.setActualCod(SanctionValueParser.formatDate(e.getActualCod()));

        w.setPlfPct(SanctionValueParser.formatPct(e.getPlfPct()));
        w.setTariffPerUnit(SanctionValueParser.formatTariff(e.getTariffPerUnit()));

        w.setStatus(e.getStatus());
        w.setActiveStatus(e.getActiveStatus());
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