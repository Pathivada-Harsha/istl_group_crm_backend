package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

        // A PDF with no text layer yields nothing for either tier. Say so plainly
        // rather than reporting "no fields recognised".
        if (!docx && text.strip().length() < 200) {
            throw new CustomException(
                    "This PDF has no readable text layer (it looks like a scan), so fields "
                  + "can't be extracted automatically. Please enter them manually.");
        }

        String engine = docx ? "TABLE" : "REGEX";

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

        List<BorrowerWrapper> out = new ArrayList<>();
        for (BorrowerEntity b : rows) {
            if (!borrowerInScope(b, userId, userRole)) continue;
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

    public BorrowerWrapper getById(Long userId, String userRole, Long id) throws CustomException {
        BorrowerEntity b = borrowerRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Borrower not found"));
        if (!borrowerInScope(b, userId, userRole)) {
            throw new CustomException("Borrower not found");
        }
        BorrowerWrapper w = buildBorrowerWrapper(b);
        w.setCanEditBorrower(inScope(b.getCreatedBy(), userId, userRole));
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
        if (!inScope(b.getCreatedBy(), userId, userRole)) {
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
            em.createNativeQuery("DELETE FROM company_groups WHERE id = :id")
              .setParameter("id", sub.getId())
              .executeUpdate();
        }

        deleteCompaniesInGroup(id);

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
        Long parentId = in.getParentGroupId();
        if (parentId != null) {
            CompanyGroupEntity parent = companyGroupRepo.findByIdAndDeletedAtIsNull(parentId)
                    .orElseThrow(() -> new CustomException("Parent group not found"));
            if (parent.getParentGroupId() != null) {
                throw new CustomException("A Sub Group cannot itself be nested under another Sub Group.");
            }
        }
        String name = in.getGroupName().trim();
        if (companyGroupRepo.findByExactNameAndParent(name, parentId).isPresent()) {
            throw new CustomException(parentId == null
                    ? "A Parent Group named \"" + name + "\" already exists."
                    : "A Sub Group named \"" + name + "\" already exists under that Parent Group.");
        }
        CompanyGroupEntity g = new CompanyGroupEntity();
        g.setGroupName(name);
        g.setParentGroupId(parentId);
        g.setCreatedBy(userId);
        return toGroupWrapper(companyGroupRepo.save(g));
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
        g.setGroupName(name);
        g.setParentGroupId(newParentId);
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

    private boolean inScope(Long recordCreatedBy, Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
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
    private boolean borrowerInScope(BorrowerEntity b, Long userId, String userRole) {
        if (inScope(b.getCreatedBy(), userId, userRole)) return true;
        return sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(b.getId()).stream()
                .anyMatch(s -> inScope(s.getCreatedBy(), userId, userRole));
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
    private boolean groupInScope(CompanyGroupEntity g, Long userId, String userRole) {
        if (inScope(g.getCreatedBy(), userId, userRole)) return true;
        if (borrowerRepo.findByGroupId(g.getId()).stream().anyMatch(b -> borrowerInScope(b, userId, userRole))) {
            return true;
        }
        if (g.getParentGroupId() == null) {
            for (CompanyGroupEntity sub
                    : companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(g.getId())) {
                if (borrowerRepo.findByGroupId(sub.getId()).stream()
                        .anyMatch(b -> borrowerInScope(b, userId, userRole))) {
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
        if (!inScope(b.getCreatedBy(), userId, userRole)) {
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
    private void assertSanctionWriteScope(Long userId, String userRole, BorrowerEntity borrower,
            BorrowerSanctionEntity sanction) throws CustomException {
        if (inScope(borrower.getCreatedBy(), userId, userRole)) return;
        if (sanction != null && inScope(sanction.getCreatedBy(), userId, userRole)) return;
        throw new CustomException("Sanction not found");
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
        BorrowerEntity borrower = borrowerRepo.findByIdAndDeletedAtIsNull(sanction.getBorrowerId())
                .orElseThrow(() -> new CustomException("Sanction not found"));
        if (!borrowerInScope(borrower, userId, userRole)) {
            throw new CustomException("Sanction not found");
        }
    }

    /**
     * Rank candidate borrowers for a parsed sanction letter's identity, most
     * trustworthy first. Never decides anything on its own — every candidate
     * is a suggestion for the reviewer to confirm or reject; see the class
     * comment on {@link CompanyMatchWrapper}.
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

        List<BorrowerEntity> all = borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc();

        // Priority 2: normalized legal-name exact match.
        for (BorrowerEntity b : all) {
            if (CompanyNameMatcher.normalizedEquals(name, b.getBorrowerName())) {
                out.add(toMatch(b, "NAME", 1.0));
            }
        }
        if (!out.isEmpty()) return out;

        // Priority 3: alias match.
        Set<Long> seen = new LinkedHashSet<>();
        for (BorrowerAliasEntity a : borrowerAliasRepo.findAll()) {
            if (!CompanyNameMatcher.normalizedEquals(name, a.getAliasName())) continue;
            if (!seen.add(a.getBorrowerId())) continue;
            borrowerRepo.findByIdAndDeletedAtIsNull(a.getBorrowerId())
                    .ifPresent(b -> out.add(toMatch(b, "ALIAS", 1.0)));
        }
        if (!out.isEmpty()) return out;

        // Priority 4: fuzzy — surfaced only, floor set high enough to skip
        // unrelated names while still catching typos/abbreviations. Never
        // auto-attached; the caller must always ask the user to confirm.
        List<CompanyMatchWrapper> fuzzy = new ArrayList<>();
        for (BorrowerEntity b : all) {
            double score = CompanyNameMatcher.similarity(name, b.getBorrowerName());
            if (score >= 0.55) fuzzy.add(toMatch(b, "FUZZY", score));
        }
        fuzzy.sort((x, y) -> Double.compare(y.getScore(), x.getScore()));
        return fuzzy.stream().limit(5).collect(Collectors.toList());
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
    }

    private Rollup rollupFor(Long borrowerId) {
        Rollup r = new Rollup();
        for (BorrowerSanctionEntity s :
                sanctionRepo.findByBorrowerIdAndDeletedAtIsNullOrderBySanctionDateDesc(borrowerId)) {
            r.sanctionsCount++;
            if (s.getSanctionedAmount() != null) r.total = r.total.add(s.getSanctionedAmount());
        }
        return r;
    }

    private Map<String, Object> toCompanySummary(BorrowerEntity b, Rollup r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("borrowerName", b.getBorrowerName());
        m.put("cin", b.getCin());
        m.put("companyType", companyTypeLabel(b.getIsSubsidiary(), b.getIsSpv()));
        m.put("sanctionsCount", r.sanctionsCount);
        m.put("totalSanctionedAmount", SanctionValueParser.formatCrore(r.total));
        m.put("status", r.sanctionsCount == 0 ? "No sanctions" : "Active");
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
        // Groups are scoped by their own createdBy too — a group the caller
        // can't see hides its whole subtree, including any company under it
        // that the caller WOULD otherwise be able to see on its own merits.
        // That's a deliberate consequence of treating group visibility as a
        // hard boundary rather than something a nested company can see past;
        // if that ever surprises someone about their own company, the fix is
        // moving the company to a group they have visibility into (or an
        // admin reassigning the group's owner).
        List<BorrowerEntity> borrowers = borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .filter(b -> borrowerInScope(b, userId, userRole))
                .collect(Collectors.toList());
        List<CompanyGroupEntity> groups = companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc().stream()
                .filter(g -> groupInScope(g, userId, userRole))
                .collect(Collectors.toList());

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
            buildGroupNode(g, node, byGroup, subGroupsByParent);
            groupNodes.add(node);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groupNodes);
        out.put("standalone", standalone.stream()
                .map(b -> toCompanySummary(b, rollupFor(b.getId())))
                .collect(Collectors.toList()));
        return out;
    }

    /** Fills {@code outNode} in place and returns this node's own rollup (its companies plus every sub-group's). */
    private Rollup buildGroupNode(CompanyGroupEntity g, Map<String, Object> outNode,
            Map<Long, List<BorrowerEntity>> byGroup, Map<Long, List<CompanyGroupEntity>> subGroupsByParent) {
        outNode.put("id", g.getId());
        outNode.put("groupName", g.getGroupName());
        outNode.put("type", g.getParentGroupId() == null ? "GROUP" : "SUB_GROUP");

        List<BorrowerEntity> companies = byGroup.getOrDefault(g.getId(), List.of());
        List<Map<String, Object>> companyNodes = new ArrayList<>();
        Rollup roll = new Rollup();
        for (BorrowerEntity b : companies) {
            Rollup r = rollupFor(b.getId());
            companyNodes.add(toCompanySummary(b, r));
            roll.sanctionsCount += r.sanctionsCount;
            roll.total = roll.total.add(r.total);
        }
        outNode.put("companies", companyNodes);

        List<CompanyGroupEntity> subs = subGroupsByParent.getOrDefault(g.getId(), List.of());
        List<Map<String, Object>> subNodes = new ArrayList<>();
        for (CompanyGroupEntity sub : subs) {
            Map<String, Object> subNode = new LinkedHashMap<>();
            Rollup subRoll = buildGroupNode(sub, subNode, byGroup, subGroupsByParent);
            subNodes.add(subNode);
            roll.sanctionsCount += subRoll.sanctionsCount;
            roll.total = roll.total.add(subRoll.total);
        }
        outNode.put("subGroups", subNodes);
        outNode.put("sanctionsCount", roll.sanctionsCount);
        outNode.put("totalSanctionedAmount", SanctionValueParser.formatCrore(roll.total));
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
        List<BorrowerEntity> borrowers = borrowerRepo.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .filter(b -> borrowerInScope(b, userId, userRole))
                .collect(Collectors.toList());
        List<CompanyGroupEntity> groups = companyGroupRepo.findByDeletedAtIsNullOrderByGroupNameAsc().stream()
                .filter(g -> groupInScope(g, userId, userRole))
                .collect(Collectors.toList());
        int sanctionsCount = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (BorrowerEntity b : borrowers) {
            Rollup r = rollupFor(b.getId());
            sanctionsCount += r.sanctionsCount;
            total = total.add(r.total);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalGroups", groups.size());
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
        m.put("sanctionsCount", r.sanctionsCount);
        m.put("totalSanctionedAmount", SanctionValueParser.formatCrore(r.total));
        return m;
    }

    /**
     * One group's own hierarchy-wide rollup — its direct companies plus every
     * one of its Sub Groups' direct companies. For a Sub Group (which can
     * never have Sub Groups of its own) this is simply its direct companies.
     */
    private Rollup rollupForGroupHierarchy(Long groupId, Long userId, String userRole) {
        Rollup roll = new Rollup();
        for (BorrowerEntity b : borrowerRepo.findByGroupId(groupId)) {
            if (!borrowerInScope(b, userId, userRole)) continue;
            Rollup r = rollupFor(b.getId());
            roll.sanctionsCount += r.sanctionsCount;
            roll.total = roll.total.add(r.total);
        }
        for (CompanyGroupEntity sub
                : companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(groupId)) {
            for (BorrowerEntity b : borrowerRepo.findByGroupId(sub.getId())) {
                if (!borrowerInScope(b, userId, userRole)) continue;
                Rollup r = rollupFor(b.getId());
                roll.sanctionsCount += r.sanctionsCount;
                roll.total = roll.total.add(r.total);
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
                        .filter(g -> groupInScope(g, userId, userRole))
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
                groupRows.add(toGroupSummary(g, rollupForGroupHierarchy(g.getId(), userId, userRole)));
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
            for (BorrowerEntity b : page1) {
                standaloneRows.add(toCompanySummary(b, rollupFor(b.getId())));
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
    private List<Long> scopeIdsFor(Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        return level <= 2 ? null : level == 3 ? resolveTeamMemberIds(userId) : List.of(userId);
    }

    /** One Parent Group or Sub Group's own summary — breadcrumb + stat-card figures, no nested rows. */
    @Transactional(readOnly = true)
    public CompanyGroupWrapper getGroupDetail(Long userId, String userRole, Long id) throws CustomException {
        CompanyGroupEntity g = companyGroupRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Group not found"));
        if (!groupInScope(g, userId, userRole)) {
            throw new CustomException("Group not found");
        }
        CompanyGroupWrapper w = toGroupWrapper(g);
        List<Long> scopeIds = scopeIdsFor(userId, userRole);

        int subGroupsCount = (int) companyGroupRepo
                .findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(id).stream()
                .filter(sub -> groupInScope(sub, userId, userRole))
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

        Rollup roll = rollupForGroupHierarchy(id, userId, userRole);
        w.setSanctionsCount(roll.sanctionsCount);
        w.setTotalSanctionedAmount(SanctionValueParser.formatCrore(roll.total));
        return w;
    }

    /** One page of the companies sitting directly under a group — Direct Companies, or one Sub Group's own table. */
    @Transactional(readOnly = true)
    public PagedResponseWrapper<Map<String, Object>> getGroupCompanies(
            Long userId, String userRole, Long groupId, int page, int size) {
        List<Long> scopeIds = scopeIdsFor(userId, userRole);
        Page<BorrowerEntity> p = scopeIds == null
                ? borrowerRepo.findByGroupIdAndDeletedAtIsNullOrderByBorrowerNameAsc(groupId, PageRequest.of(page, size))
                : borrowerRepo.findByGroupIdAndCreatedByInAndDeletedAtIsNullOrderByBorrowerNameAsc(
                        groupId, scopeIds, PageRequest.of(page, size));
        List<Map<String, Object>> content = p.getContent().stream()
                .map(b -> toCompanySummary(b, rollupFor(b.getId())))
                .collect(Collectors.toList());
        return PagedResponseWrapper.of(content, page, size, p.getTotalElements());
    }

    /** One page of the Sub Groups sitting directly under a Parent Group, each with its own summary (not its companies). */
    @Transactional(readOnly = true)
    public PagedResponseWrapper<CompanyGroupWrapper> getSubGroups(
            Long userId, String userRole, Long groupId, int page, int size) {
        List<Long> scopeIds = scopeIdsFor(userId, userRole);
        Page<CompanyGroupEntity> p = scopeIds == null
                ? companyGroupRepo.findByParentGroupIdAndDeletedAtIsNullOrderByGroupNameAsc(
                        groupId, PageRequest.of(page, size))
                : companyGroupRepo.findByParentGroupIdAndCreatedByInAndDeletedAtIsNullOrderByGroupNameAsc(
                        groupId, scopeIds, PageRequest.of(page, size));
        List<CompanyGroupWrapper> content = p.getContent().stream().map(sub -> {
            CompanyGroupWrapper w = toGroupWrapper(sub);
            // A Sub Group can never have Sub Groups of its own, so this rollup
            // is simply its own direct companies.
            Rollup r = rollupForGroupHierarchy(sub.getId(), userId, userRole);
            w.setCompaniesCount(scopeIds == null
                    ? (int) borrowerRepo.countByGroupIdAndDeletedAtIsNull(sub.getId())
                    : (int) borrowerRepo.countByGroupIdAndCreatedByInAndDeletedAtIsNull(sub.getId(), scopeIds));
            w.setSanctionsCount(r.sanctionsCount);
            w.setTotalSanctionedAmount(SanctionValueParser.formatCrore(r.total));
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
    public BorrowerWrapper saveSanction(BorrowerSanctionWrapper in, Long userId, String userRole,
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
            assertSanctionWriteScope(userId, userRole, borrower, e);
        }
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
        return buildBorrowerWrapper(borrower.getId());
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
            b = new BorrowerEntity();
            b.setBorrowerName(name);
            fillBlanks(b, in);
            b.setCreatedBy(userId);
        }
        return buildBorrowerWrapper(borrowerRepo.save(b).getId());
    }

    /** Copy the parsed borrower-level values onto blank fields only. */
    private boolean fillBlanks(BorrowerEntity b, BorrowerWrapper in) {
        boolean changed = false;
        // CIN is the natural key, so only fill it in when it won't collide
        // with a CIN already on file for a different borrower.
        if (SanctionValueParser.isBlank(b.getCin()) && !SanctionValueParser.isBlank(in.getCin())) {
            String cin = SanctionValueParser.clean(in.getCin());
            Optional<BorrowerEntity> other = borrowerRepo.findByCinIgnoreCaseAndDeletedAtIsNull(cin);
            if (other.isEmpty() || other.get().getId().equals(b.getId())) {
                b.setCin(cin);
                changed = true;
            }
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
        BorrowerEntity owner = borrowerRepo.findByIdAndDeletedAtIsNull(s.getBorrowerId())
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionWriteScope(userId, userRole, owner, s);
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

    @Transactional
    public BorrowerSanctionWrapper uploadDocument(Long sanctionId, MultipartFile file, Long userId, String userRole)
            throws CustomException {
        validateFile(file);
        BorrowerSanctionEntity s = sanctionRepo.findByIdAndDeletedAtIsNull(sanctionId)
                .orElseThrow(() -> new CustomException("Sanction not found"));
        BorrowerEntity owner = borrowerRepo.findByIdAndDeletedAtIsNull(s.getBorrowerId())
                .orElseThrow(() -> new CustomException("Sanction not found"));
        assertSanctionWriteScope(userId, userRole, owner, s);
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

    private void applyBorrower(BorrowerEntity b, BorrowerWrapper in) {
        b.setBorrowerName(SanctionValueParser.clean(in.getBorrowerName()));
        b.setCin(SanctionValueParser.clean(in.getCin()));
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