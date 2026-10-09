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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.istlgroup.istl_group_crm_backend.service.tender.ExtractedField;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderBoqLocator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderBoqValidator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderDocumentsValidator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderSectionLocator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderEligibilityValidator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderFieldValidator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderParseGate;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderParseResult;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderText;
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
    public TenderWrapper create(TenderWrapper w, Long userId, String actorName) throws CustomException {
        TenderEntity t = new TenderEntity();
        applyScalars(t, w);
        t.setCreatedBy(userId);
        TenderEntity saved = tenderRepo.save(t);
        saveChildren(saved.getId(), w);
        recordImport(saved.getId(), w, actorName);
        return toWrapper(tenderRepo.findById(saved.getId()).orElse(saved), true);
    }

    @Transactional
    public TenderWrapper update(Long id, TenderWrapper w, Long userId, String actorName) throws CustomException {
        TenderEntity t = tenderRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException("Tender not found"));
        applyScalars(t, w);
        tenderRepo.save(t);
        // whole-object save: replace all child collections
        deleteChildren(id);
        saveChildren(id, w);
        recordImport(id, w, actorName);
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

    /**
     * Stateless parse of an uploaded NIT.
     *
     * <p>Runs stages 1–5: clean the text, locate the summary block, extract,
     * validate, and decide whether the parse is complete. Nothing is written to
     * the form here — the caller gets a list of proposed values, each with the
     * page and line it came from, and the user picks.
     *
     * <p>The AI never runs on its own. {@code useAi} is set only when the user
     * asks for a re-read from the review modal, which they can do whether the
     * parse came back incomplete or came back looking plausible but wrong.
     */
    public TenderParseResult parsePdf(MultipartFile file, boolean useAi) throws CustomException {
        validatePdf(file);
        TenderText document;
        try {
            document = TenderPdfExtractor.load(file.getBytes());
        } catch (IOException e) {
            throw new CustomException("Could not read the PDF: " + e.getMessage());
        }
        // A PDF with no text layer (a scan/photocopy) yields nothing for either
        // parser — say so plainly instead of reporting "no fields recognised".
        if (document.charCount() < MIN_TEXT_LAYER_CHARS) {
            throw new CustomException(
                    "This PDF has no readable text layer (it looks like a scan), so fields "
                  + "can't be extracted automatically. Please enter them manually.");
        }

        TenderPdfExtractor.Extraction extraction = pdfExtractor.extract(document);
        List<TenderParseResult.Discarded> discarded = new ArrayList<>();
        Map<String, ExtractedField> fields = validate(extraction.fields(), extraction, discarded);

        List<Map<String, Object>> boqItems = extraction.boqItems();
        List<Map<String, Object>> eligibility = extraction.eligibilityCriteria();
        List<Map<String, Object>> documents = List.of();   // only the AI reads the checklist
        List<String> notes = new ArrayList<>();

        String origin = ExtractedField.REGEX;
        String aiError = null;
        if (useAi) {
            // Four independent reads of four different parts of the document,
            // run side by side: one failing never costs the others, and the
            // click waits for the slowest call rather than the sum of them.
            TenderText eligibilitySection = extraction.eligibilityText();
            TenderText documentsSection = extraction.documentsText();
            TenderText boqSection = extraction.boqText();
            CompletableFuture<Map<String, Object>> scalarCall = ai(() ->
                    pdfAiExtractor.extractFromText(extraction.summaryText().asText()));
            CompletableFuture<List<Map<String, Object>>> eligibilityCall = eligibilitySection.isEmpty()
                    ? CompletableFuture.completedFuture(List.of())
                    : ai(() -> pdfAiExtractor.extractEligibility(eligibilitySection.asText()));
            CompletableFuture<List<Map<String, Object>>> documentsCall = documentsSection.isEmpty()
                    ? CompletableFuture.completedFuture(List.of())
                    : ai(() -> pdfAiExtractor.extractDocuments(documentsSection.asText()));
            CompletableFuture<List<Map<String, Object>>> boqCall = boqSection.isEmpty()
                    ? CompletableFuture.completedFuture(List.of())
                    : ai(() -> pdfAiExtractor.extractBoq(boqSection.asText()));

            try {
                // The merged map is revalidated from scratch, so the first pass's
                // rejections are superseded rather than reported twice.
                List<TenderParseResult.Discarded> fromAi = new ArrayList<>();
                fields = mergeAi(fields, scalarCall.join(), extraction, fromAi);
                discarded = fromAi;
                origin = "regex+ai";
            } catch (Exception e) {
                aiError = cause(e);
                log.warn("AI tender parse failed: {}", aiError);
            }

            // Each section's rows are checked against that section's text.
            // When they survive they replace the layout-only rows outright.
            try {
                TenderEligibilityValidator.Result read =
                        TenderEligibilityValidator.validate(eligibilityCall.join(), eligibilitySection);
                if (!read.criteria().isEmpty()) eligibility = read.criteria();
                discarded.addAll(read.discarded());
            } catch (Exception e) {
                notes.add("The eligibility read failed (" + cause(e) + ").");
            }
            try {
                TenderDocumentsValidator.Result read =
                        TenderDocumentsValidator.validate(documentsCall.join(), documentsSection);
                documents = read.documents();
                discarded.addAll(read.discarded());
            } catch (Exception e) {
                notes.add("The documents read failed (" + cause(e) + ").");
            }
            try {
                TenderBoqValidator.Result read = TenderBoqValidator.validate(
                        boqCall.join(), extraction.boq().rows(), boqSection);
                if (!read.rows().isEmpty()) boqItems = read.rows();
                discarded.addAll(read.discarded());
            } catch (Exception e) {
                notes.add("The BOQ read failed (" + cause(e) + ") — quantities are from the schedule "
                        + "lines, descriptions are best-effort.");
            }
        }
        notes.addAll(sectionNotes(extraction, useAi, eligibility, documents, boqItems));

        // Supporting dates exist only so the ordering check has something to
        // order against; they are not fields the CRM stores, so neither their
        // values nor their rejections belong in the review.
        TenderPdfExtractor.SUPPORTING_DATES.forEach(fields::remove);
        discarded.removeIf(d -> TenderPdfExtractor.SUPPORTING_DATES.contains(d.field()));

        boolean complete = TenderParseGate.isComplete(fields.keySet());
        log.info("Tender parse ({}): {} field(s) kept, {} discarded, complete={}; "
                        + "{} eligibility, {} documents, {} BOQ row(s)",
                origin, fields.size(), discarded.size(), complete,
                eligibility.size(), documents.size(), boqItems.size());

        return new TenderParseResult(complete,
                message(complete, fields, useAi, aiError),
                origin,
                new ArrayList<>(fields.values()),
                boqItems,
                eligibility,
                documents,
                notes,
                discarded,
                document.pageCount(),
                extraction.summary() == null ? null : extraction.summary().fromPage(),
                extraction.summary() == null ? null : extraction.summary().toPage(),
                extraction.eligibilitySections());
    }

    /** AI calls block on HTTP for seconds; they get their own threads, not the common pool. */
    private static final ExecutorService AI_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tender-ai");
        t.setDaemon(true);
        return t;
    });

    private static <T> CompletableFuture<T> ai(java.util.function.Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, AI_POOL);
    }

    private static String cause(Throwable e) {
        Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
        return c.getMessage();
    }

    /**
     * One line per child section: where it was read from, and what to do when
     * nothing could be. An empty section should read as "the document doesn't
     * have one" or "try the AI", never as a silent blank.
     */
    private static List<String> sectionNotes(TenderPdfExtractor.Extraction x, boolean usedAi,
                                             List<Map<String, Object>> eligibility,
                                             List<Map<String, Object>> documents,
                                             List<Map<String, Object>> boq) {
        List<String> out = new ArrayList<>();
        String elig = pages(x.eligibilitySections());
        if (elig == null) {
            out.add("Eligibility: no qualifying-requirements section was recognised — enter them by hand.");
        } else if (!usedAi) {
            out.add("Eligibility (" + elig + "): only stated figures were read — the AI re-read extracts every clause.");
        } else {
            out.add("Eligibility: " + eligibility.size() + " criteria read from " + elig + ".");
        }

        String docs = pages(x.documentSections());
        if (docs == null) {
            out.add("Documents: no list of documents to submit was recognised.");
        } else if (!usedAi) {
            out.add("Documents (" + docs + "): the AI re-read builds the checklist from this section.");
        } else {
            out.add("Documents: " + documents.size() + " read from " + docs + ".");
        }

        TenderBoqLocator.Result b = x.boq();
        if (b.found()) {
            String where = b.fromPage().equals(b.toPage()) ? "p." + b.fromPage() : "pp." + b.fromPage() + "–" + b.toPage();
            out.add("BOQ: " + boq.size() + " rows from " + where + (usedAi
                    ? "."
                    : " — quantities are exact; the AI re-read writes full descriptions."));
        } else if (b.elsewhere() != null) {
            out.add("BOQ: not in this PDF — " + b.elsewhere() + ". Import it on the Rate Analysis tab from Excel.");
        } else {
            out.add("BOQ: no bill of quantities was found in this PDF.");
        }
        return out;
    }

    private static String pages(List<TenderSectionLocator.Section> sections) {
        if (sections.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (TenderSectionLocator.Section s : sections) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.fromPage() == s.toPage() ? "p." + s.fromPage() : "pp." + s.fromPage() + "–" + s.toPage());
        }
        return sb.toString();
    }

    /**
     * Stage 4 for a whole field map: per-field rules plus the cross-field checks,
     * with the work title tidied however it was produced. The LLM in particular
     * tends to hand back the whole cover page — description, tender reference,
     * agency, address, phone, email, URL and the bidder's signature line as one
     * string — so the tidy-up runs on every path rather than trusting the prompt.
     */
    private Map<String, ExtractedField> validate(Map<String, ExtractedField> raw,
                                                 TenderPdfExtractor.Extraction extraction,
                                                 List<TenderParseResult.Discarded> discarded) {
        Map<String, ExtractedField> input = new LinkedHashMap<>(raw);
        ExtractedField name = input.get("tenderName");
        if (name != null) {
            String tidy = TenderPdfExtractor.tidyTenderName(name.value());
            if (tidy == null || tidy.isEmpty()) input.remove("tenderName");
            else input.put("tenderName", name.withValue(tidy));
        }
        ExtractedField ref = input.get("tenderNumber");
        TenderFieldValidator.Context ctx = TenderFieldValidator.contextFor(
                ref == null ? null : ref.value(), extraction.summaryText().flat());

        return TenderFieldValidator.validate(input, ctx, (field, why) -> {
            ExtractedField dropped = input.get(field);
            discarded.add(new TenderParseResult.Discarded(field,
                    dropped == null ? null : dropped.value(), why));
            log.debug("Tender parse: discarded {} — {}", field, why);
        });
    }

    /** What an AI re-read produced, once it has been through the same rules. */
    /**
     * Fold the AI's read of the summary block in. The AI wins a disagreement,
     * but a value it changes is handed over <em>unticked</em>: the two
     * extractors disagreeing is the clearest signal there is that a human
     * should look at that row.
     */
    private Map<String, ExtractedField> mergeAi(Map<String, ExtractedField> regexFields,
                                                Map<String, Object> ai,
                                                TenderPdfExtractor.Extraction extraction,
                                                List<TenderParseResult.Discarded> discarded) throws CustomException {
        TenderText block = extraction.summaryText();
        if (ai == null || ai.isEmpty()) {
            throw new CustomException("the AI returned no recognisable fields");
        }

        Map<String, ExtractedField> merged = new LinkedHashMap<>(regexFields);
        for (Map.Entry<String, Object> e : ai.entrySet()) {
            if (!(e.getValue() instanceof String value) || value.isBlank()) continue;
            ExtractedField existing = merged.get(e.getKey());
            boolean changes = existing != null && !existing.value().equals(value.strip());
            merged.put(e.getKey(), locate(block, e.getKey(), value.strip(), !changes));
        }
        return validate(merged, extraction, discarded);
    }

    /** Give an AI value a page and a source line by finding it in the block. */
    private ExtractedField locate(TenderText block, String field, String value, boolean confident) {
        String needle = value.length() > 30 ? value.substring(0, 30) : value;
        for (TenderText.Line line : block.lines()) {
            if (line.text().contains(needle)) {
                return new ExtractedField(field, "read by the AI", value,
                        line.page(), line.text(), ExtractedField.AI, confident);
            }
        }
        int page = block.lines().isEmpty() ? 1 : block.lines().get(0).page();
        return new ExtractedField(field, "read by the AI", value, page,
                "(the AI derived this; it is not quoted verbatim in the document)",
                ExtractedField.AI, false);
    }

    private String message(boolean complete, Map<String, ExtractedField> fields,
                           boolean usedAi, String aiError) {
        if (aiError != null) {
            return "The AI re-read failed (" + aiError + "). Showing what the parser found.";
        }
        if (fields.isEmpty()) {
            return usedAi
                    ? "Neither the parser nor the AI could read any field from this PDF."
                    : "No field could be read from this PDF. Try the AI re-read.";
        }
        if (complete) {
            return "Read " + fields.size() + " field" + (fields.size() == 1 ? "" : "s")
                 + " — check each one against the source before applying.";
        }
        List<String> missing = TenderParseGate.missingCore(fields.keySet());
        return "This parse is incomplete: "
             + (missing.isEmpty() ? "no cost or date could be confirmed"
                                  : String.join(", ", missing) + " could not be confirmed")
             + ". Anything unreliable was left out rather than guessed.";
    }

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

    /** Fewer extracted characters than this and the PDF is a scan with no text layer. */
    public static final int MIN_TEXT_LAYER_CHARS = 200;

    public static void validatePdf(MultipartFile file) throws CustomException {
        if (file == null || file.isEmpty()) throw new CustomException("No file provided");
        if (file.getSize() > MAX_PDF_BYTES) throw new CustomException("File exceeds the 50 MB limit");
        String mime = file.getContentType();
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        boolean isPdf = (mime != null && mime.toLowerCase().contains("pdf")) || name.endsWith(".pdf");
        if (!isPdf) throw new CustomException("Only PDF files are supported");
    }

    /**
     * When this save applies an Excel import, say so in the tender's history:
     * which file, what it carried, and who imported it. The name comes from the
     * session, not the request.
     */
    private void recordImport(Long tenderId, TenderWrapper w, String actorName) {
        if (!"excel".equalsIgnoreCase(w.getImportSource())) return;
        TenderApprovalLogEntity e = new TenderApprovalLogEntity();
        e.setTenderId(tenderId);
        e.setStage("Import");
        e.setAction("Imported from Excel");
        e.setActionBy(actorName);
        String file = w.getImportFileName() == null || w.getImportFileName().isBlank()
                ? "(unnamed file)" : w.getImportFileName().strip();
        String summary = w.getImportSummary() == null ? "" : w.getImportSummary().strip();
        e.setRemarks(clip("File: " + file + (summary.isEmpty() ? "" : " · " + summary), 2000));
        e.setCreatedAt(LocalDateTime.now());
        approvalLogRepo.save(e);

        // A bulk tick confirms rows nobody could match to the PDF, so who used it,
        // on which table and for how many rows is kept on the record.
        if (w.getImportBulkAcks() == null) return;
        for (Map<String, Object> ack : w.getImportBulkAcks()) {
            if (ack == null) continue;
            Object table = ack.get("table");
            Object rows = ack.get("rows");
            if (table == null || rows == null) continue;
            TenderApprovalLogEntity b = new TenderApprovalLogEntity();
            b.setTenderId(tenderId);
            b.setStage("Import");
            b.setAction("Bulk-confirmed unverified rows");
            b.setActionBy(actorName);
            b.setRemarks(clip("Table: " + table + " · " + rows + " row" + ("1".equals(rows.toString()) ? "" : "s")
                    + " not found in the PDF, confirmed with one tick · File: " + file, 2000));
            b.setCreatedAt(LocalDateTime.now());
            approvalLogRepo.save(b);
        }
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
                e.setAltGroup(clip(c.getAltGroup(), 40));
                e.setTier(clip(c.getTier(), 60));
                e.setClauseText(c.getClauseText());
                e.setSourcePage(integer(c.getSourcePage()));
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
        t.setEmdStatus(clip(w.getEmdStatus(), 40));
        t.setEmdPaidAmount(bd(w.getEmdPaidAmount()));
        t.setEmdPaidDate(dt(w.getEmdPaidDate()));
        t.setEmdPaymentMode(clip(w.getEmdPaymentMode(), 60));
        t.setEmdReference(clip(w.getEmdReference(), 120));
        t.setEmdPaidFromAccount(clip(w.getEmdPaidFromAccount(), 200));
        t.setEmdBeneficiaryName(clip(w.getEmdBeneficiaryName(), 200));
        t.setEmdBeneficiaryBank(clip(w.getEmdBeneficiaryBank(), 200));
        t.setEmdBeneficiaryAccount(clip(w.getEmdBeneficiaryAccount(), 60));
        t.setEmdBeneficiaryIfsc(clip(w.getEmdBeneficiaryIfsc(), 20));
        t.setEmdValidTill(dt(w.getEmdValidTill()));
        t.setEmdRefundAmount(bd(w.getEmdRefundAmount()));
        t.setEmdRefundDate(dt(w.getEmdRefundDate()));
        t.setEmdRefundReference(clip(w.getEmdRefundReference(), 120));
        t.setEmdRefundAccount(clip(w.getEmdRefundAccount(), 200));
        t.setEmdNotes(w.getEmdNotes());
        t.setFeeAmount(bd(w.getFeeAmount()));
        t.setFeeRefundable(clip(w.getFeeRefundable(), 10));
        t.setFeeBeneficiaryName(clip(w.getFeeBeneficiaryName(), 200));
        t.setFeeBeneficiaryBank(clip(w.getFeeBeneficiaryBank(), 200));
        t.setFeeBeneficiaryAccount(clip(w.getFeeBeneficiaryAccount(), 60));
        t.setFeeBeneficiaryIfsc(clip(w.getFeeBeneficiaryIfsc(), 20));
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
        w.setEmdStatus(t.getEmdStatus());
        w.setEmdPaidAmount(s(t.getEmdPaidAmount()));
        w.setEmdPaidDate(s(t.getEmdPaidDate()));
        w.setEmdPaymentMode(t.getEmdPaymentMode());
        w.setEmdReference(t.getEmdReference());
        w.setEmdPaidFromAccount(t.getEmdPaidFromAccount());
        w.setEmdBeneficiaryName(t.getEmdBeneficiaryName());
        w.setEmdBeneficiaryBank(t.getEmdBeneficiaryBank());
        w.setEmdBeneficiaryAccount(t.getEmdBeneficiaryAccount());
        w.setEmdBeneficiaryIfsc(t.getEmdBeneficiaryIfsc());
        w.setEmdValidTill(s(t.getEmdValidTill()));
        w.setEmdRefundAmount(s(t.getEmdRefundAmount()));
        w.setEmdRefundDate(s(t.getEmdRefundDate()));
        w.setEmdRefundReference(t.getEmdRefundReference());
        w.setEmdRefundAccount(t.getEmdRefundAccount());
        w.setEmdNotes(t.getEmdNotes());
        w.setFeeAmount(s(t.getFeeAmount()));
        w.setFeeRefundable(t.getFeeRefundable());
        w.setFeeBeneficiaryName(t.getFeeBeneficiaryName());
        w.setFeeBeneficiaryBank(t.getFeeBeneficiaryBank());
        w.setFeeBeneficiaryAccount(t.getFeeBeneficiaryAccount());
        w.setFeeBeneficiaryIfsc(t.getFeeBeneficiaryIfsc());
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
                c.setAltGroup(e.getAltGroup());
                c.setTier(e.getTier());
                c.setClauseText(e.getClauseText());
                c.setSourcePage(e.getSourcePage() == null ? null : e.getSourcePage().toString());
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

    private Integer integer(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        try { return Integer.valueOf(t); } catch (Exception e) { return null; }
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
