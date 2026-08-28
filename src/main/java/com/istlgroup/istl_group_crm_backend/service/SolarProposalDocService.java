package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProposalDocumentEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.entity.SiteVisitEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProposalDocumentRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.SiteVisitRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.service.docx.AmountInWords;
import com.istlgroup.istl_group_crm_backend.service.docx.DocxTemplate;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SolarProposalDocRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Generates the client-facing Solar proposal from the lead's own tabs.
 *
 * <p>The document is produced by filling a fixed .docx skeleton
 * ({@code resources/proposal-templates/solar-proposal-template.docx}) that was cut
 * from the signed-off reference proposal, so ~90% of every proposal — cover
 * pages, company sections, the net-metering and sample-works images, the running
 * header, the footer band, the warranty note and the signatory block — is
 * reproduced unchanged. Only the regions listed in {@link SolarProposalDocRequest}
 * are written.
 *
 * <p>Scope: the Solar group only. Other groups keep the generic proposal path.
 */
@Service
@Slf4j
public class SolarProposalDocService {

    /**
     * Solar work is an EPC <em>sub-group</em>, not a group: the live taxonomy is
     * EPC → {@code Solar_Rooftop | Solar_ground_mounted | Solar_carports | Solar Wind
     * | Pm_kusum | Substations}, IoT → {@code CCMS | ITMS | MCMS}, plus CBG. The
     * "Solar" group named in {@code ProposalsEntity}'s comment does not exist in
     * the data, so the sub-group prefix is what decides the proposal path.
     */
    private static final String SOLAR_SUBGROUP_PREFIX = "solar";
    private static final String TEMPLATE = "proposal-templates/solar-proposal-template.docx";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_MIME = "application/pdf";

    /** MNRE PM Surya Ghar slabs: ₹30k/kW for the first 2 kW, ₹18k for the 3rd, capped. */
    private static final BigDecimal SUBSIDY_CAP = BigDecimal.valueOf(78000);

    /**
     * The property types a lead can carry, mirroring {@code PROPERTY_TYPES} in the
     * frontend's {@code LeadSiteVisitTab.js}. Only used to recognise a type spelled
     * inside the Technical Scope's free-text project type; the telecaller field is
     * taken verbatim.
     */
    private static final String[] PROPERTY_TYPES = {
            "Residential", "Commercial", "Industrial",
            "Institutional", "Agricultural", "Government" };

    /** Units generated per kWp per year — the house assumption, editable per proposal. */
    private static final BigDecimal DEFAULT_SPECIFIC_GENERATION = BigDecimal.valueOf(1460);
    private static final int DEFAULT_SYSTEM_LIFE_YEARS = 25;
    private static final BigDecimal DEFAULT_GST_PERCENT = new BigDecimal("8.9");
    private static final int DEFAULT_QUOTE_VALID_DAYS = 10;

    private static final String SUBSIDY_NOTE_1 =
            "Subsidy will be credited directly to the customer's bank account as per MNRE norms.";
    private static final String SUBSIDY_NOTE_2 =
            "Subsidy is subject to approval from the concerned authorities and compliance with prevailing MNRE guidelines.";
    private static final String SUBSIDY_NOTE_3 =
            "Customer has to register on the National Portal for Rooftop Solar and complete all required formalities for subsidy claim.";

    @Autowired private LeadsRepo leadsRepo;
    @Autowired private LeadScopeRepo leadScopeRepo;
    @Autowired private LeadBomRepo leadBomRepo;
    @Autowired private SiteVisitRepo siteVisitRepo;
    @Autowired private ProposalsRepo proposalsRepo;
    @Autowired private ProposalDocumentRepo proposalDocumentRepo;
    @Autowired private UsersRepo usersRepo;
    @Autowired private LeadScopeService leadScopeService;
    /** Renders the preview rendition from the same tokens as the .docx. */
    @Autowired private SolarProposalPdfService solarProposalPdfService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Cached because the skeleton carries the cover artwork (~9 MB on disk). */
    private volatile byte[] templateBytes;

    // ═════════════════════════════════════════════════════════════════════════
    // PREFILL — what the review step opens with
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Defaults for every variable region, drawn from the lead's tabs. Values with
     * no stored source (ROI inputs, subsidy) come back as declared assumptions or
     * blank — never as a fabricated number — and the review step shows them all.
     */
    public Map<String, Object> prefill(Long leadId, Long proposalId) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        requireSolar(lead);

        // A previous generation wins: the preparer's edits shouldn't be lost.
        // Payload only — the stored file is ~9 MB and must not be loaded here.
        Pageable one = PageRequest.of(0, 1);
        List<String> payloads = proposalId != null
                ? proposalDocumentRepo.findPayloadsByProposal(proposalId, one)
                : proposalDocumentRepo.findPayloadsByLead(leadId, one);
        String previousPayload = payloads.isEmpty() ? null : payloads.get(0);
        int lastVersion = proposalId == null ? 0 : proposalDocumentRepo.maxVersion(proposalId);

        LeadScopeEntity scope = leadScopeRepo.findByLeadIdAndDeletedAtIsNull(leadId).orElse(null);
        List<LeadBomEntity> bom = leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        SiteVisitEntity visit = siteVisitRepo.findByLeadIdOrderByVisitDateDescIdDesc(leadId)
                .stream().findFirst().orElse(null);

        BigDecimal capacityKw = resolveCapacityKw(lead, scope);
        String capacityLabel = capacityLabel(capacityKw, scope);
        String propertyType = resolvePropertyType(lead, scope);
        BigDecimal tariff = firstNumber(visit == null ? null : visit.getElectricityTariff());

        BigDecimal basePrice = budgetPrice(leadId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leadId", leadId);
        out.put("proposalId", proposalId);
        out.put("leadCode", lead.getLeadCode());
        out.put("groupName", lead.getGroupName());
        // A new proposal still prefills from the lead's last document (the tariff,
        // subsidy and wording the preparer corrected), but it is version 1.
        out.put("regeneration", proposalId != null && lastVersion > 0);
        out.put("lastVersion", lastVersion);

        out.put("propertyType", propertyType);
        out.put("clientName", nvl(lead.getName()));
        out.put("siteLocation", resolveLocation(lead, scope, visit));
        out.put("capacityKw", capacityKw);
        out.put("capacityLabel", capacityLabel);
        out.put("capacityLine", capacityLabel + " On grid Rooftop Solar Plant");
        out.put("coverTitle", (nvl(lead.getName()) + " " + nvl(propertyType)).trim());
        out.put("coverSubtitle", capacityLabel + " Ongrid Rooftop Solar Plant");
        out.put("title", (nvl(lead.getName()) + " — " + capacityLabel + " Rooftop Solar Proposal").trim());

        out.put("workDescription", capacityLabel + " Rooftop Solar PV Plant with DCR panels");
        out.put("basePrice", basePrice);
        out.put("gstPercent", DEFAULT_GST_PERCENT);
        out.put("totalCost", basePrice == null ? null
                : basePrice.multiply(BigDecimal.ONE.add(DEFAULT_GST_PERCENT.movePointLeft(2)))
                        .setScale(0, RoundingMode.HALF_UP));
        out.put("budgetSource", basePrice == null
                ? "No Budget Estimation recorded for this lead yet."
                : "Budget Estimation tab — proposal price (cost + margin).");

        out.put("quoteValidDays", DEFAULT_QUOTE_VALID_DAYS);
        out.put("quoteValidFrom", LocalDate.now().toString());

        // ── Subsidy: residential rooftops only; the preparer confirms the amount ──
        boolean subsidy = subsidyEligible(propertyType);
        out.put("includeSubsidy", subsidy);
        out.put("subsidyAmount", subsidy ? defaultSubsidy(capacityKw) : null);
        out.put("subsidyNote1", SUBSIDY_NOTE_1);
        out.put("subsidyNote2", SUBSIDY_NOTE_2);
        out.put("subsidyNote3", SUBSIDY_NOTE_3);

        // ── ROI: entered vs assumed vs computed, all visible before generating ──
        // On for every property type once the two inputs exist — see roiAvailable().
        out.put("includeRoi", roiAvailable(capacityKw, tariff));
        out.put("tariffPerUnit", tariff);
        out.put("specificGeneration", DEFAULT_SPECIFIC_GENERATION);
        out.put("systemLifeYears", DEFAULT_SYSTEM_LIFE_YEARS);
        Map<String, Object> roiSources = new LinkedHashMap<>();
        // Keyed off the parsed number, not off the visit: a site visit with a blank
        // or unreadable tariff is just as much "not recorded", and this hint is what
        // explains an ROI section that defaulted off.
        roiSources.put("tariffPerUnit", tariff == null ? "Not recorded — enter the tariff"
                : "Site Visit: " + nvl(visit.getElectricityTariff()));
        roiSources.put("monthlyConsumptionUnits", visit == null ? null : visit.getMonthlyConsumptionUnits());
        roiSources.put("sanctionedLoad", visit == null ? null : visit.getSanctionedLoad());
        roiSources.put("specificGeneration", "Assumption — " + DEFAULT_SPECIFIC_GENERATION + " units/kWp/year");
        roiSources.put("systemLifeYears", "Assumption — " + DEFAULT_SYSTEM_LIFE_YEARS + " years");
        out.put("roiSources", roiSources);

        // ── BOM: the lead's rows, exactly as entered ────────────────────────
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LeadBomEntity b : bom) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("component", nvl(b.getItemName()));
            r.put("specification", nvl(b.getSpecification()));
            r.put("make", nvl(b.getMake()));
            r.put("quantity", trimNumber(b.getQuantity()));
            r.put("unit", nvl(b.getUnit()));
            rows.add(r);
        }
        out.put("bomRows", rows);
        out.put("bomSource", rows.isEmpty()
                ? "No BOM lines on this lead — add them on the BOM tab first."
                : rows.size() + " line(s) from the lead's BOM tab.");

        if (previousPayload != null) {
            try {
                out.put("previous", mapper.readValue(previousPayload, Map.class));
            } catch (Exception e) {
                log.warn("Could not parse previous proposal payload for lead {}: {}", leadId, e.getMessage());
            }
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GENERATE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fills the skeleton and stores the result as the next version against the
     * proposal record (creating the record on the first run).
     */
    @Transactional
    public Map<String, Object> generate(SolarProposalDocRequest req, Long userId) throws CustomException {
        if (req.getLeadId() == null) throw new CustomException("leadId is required");
        LeadsEntity lead = leadsRepo.findById(req.getLeadId())
                .orElseThrow(() -> new CustomException("Lead not found"));
        requireSolar(lead);
        if (req.getBomRows() == null || req.getBomRows().isEmpty()) {
            throw new CustomException("The proposal needs at least one Bill of Material line — add them on the lead's BOM tab.");
        }

        Money money = Money.of(req.getBasePrice(), req.getGstPercent(), req.getTotalCost());
        if (money.total.signum() <= 0) {
            throw new CustomException("Enter a price — the Budget Estimation tab has no value for this lead.");
        }

        boolean withSubsidy = req.getIncludeSubsidy() != null
                ? req.getIncludeSubsidy() : subsidyEligible(req.getPropertyType());
        boolean withRoi = req.getIncludeRoi() != null
                ? req.getIncludeRoi() : roiAvailable(req.getCapacityKw(), req.getTariffPerUnit());

        if (withRoi) {
            if (req.getCapacityKw() == null || req.getCapacityKw().signum() <= 0)
                throw new CustomException("ROI needs the plant capacity in kWp");
            if (req.getTariffPerUnit() == null || req.getTariffPerUnit().signum() <= 0)
                throw new CustomException("ROI needs the electricity tariff (₹/unit) — it is blank in the review step");
        }
        if (withSubsidy && (req.getSubsidyAmount() == null || req.getSubsidyAmount().signum() < 0)) {
            throw new CustomException("Enter the applicable subsidy amount, or switch the Subsidy section off");
        }

        Map<String, String> tokens = buildTokens(req, money, withRoi);
        Map<String, Boolean> blocks = Map.of("SUBSIDY", withSubsidy, "ROI", withRoi);
        Map<String, List<Map<String, String>>> repeats = Map.of("BOM", bomRows(req));

        byte[] docx;
        try {
            docx = DocxTemplate.fill(template(), tokens, blocks, repeats);
        } catch (IOException e) {
            log.error("Failed to fill solar proposal template for lead {}", req.getLeadId(), e);
            throw new CustomException("Failed to build the proposal document: " + e.getMessage());
        }

        // The PDF rendition, for in-browser preview. Rendered from the SAME token
        // map so it can never show a different total or date than the .docx.
        // A PDF failure must never cost the preparer the Word file they are waiting
        // on, so this is best-effort: null just means "no preview yet", and the
        // first preview re-renders it from the stored payload.
        byte[] pdf = null;
        try {
            pdf = solarProposalPdfService.render(tokens, blocks, repeats);
        } catch (Exception e) {
            log.error("Solar proposal PDF render failed for lead {} (the .docx is unaffected)",
                      req.getLeadId(), e);
        }

        ProposalsEntity proposal = resolveProposal(req, lead, userId, money.total);
        int version = proposalDocumentRepo.maxVersion(proposal.getId()) + 1;

        ProposalDocumentEntity doc = new ProposalDocumentEntity();
        doc.setProposalId(proposal.getId());
        doc.setLeadId(lead.getId());
        doc.setVersion(version);
        doc.setFileName(fileName(req, proposal, version));
        doc.setContentType(DOCX_MIME);
        doc.setFileSize((long) docx.length);
        doc.setFileData(docx);
        if (pdf != null) {
            doc.setPdfData(pdf);
            doc.setPdfSize((long) pdf.length);
            doc.setPdfGeneratedAt(LocalDateTime.now());
        }
        doc.setGeneratedBy(userId);
        doc.setGeneratedByName(userName(userId));
        try {
            doc.setPayload(mapper.writeValueAsString(req));
        } catch (Exception e) {
            log.warn("Could not serialize solar proposal payload: {}", e.getMessage());
        }
        proposalDocumentRepo.save(doc);

        // Keep the tracked record in step: its version mirrors the latest file.
        proposal.setVersion(version);
        proposal.setTotalValue(money.total);
        if (req.getTitle() != null && !req.getTitle().isBlank()) proposal.setTitle(req.getTitle().trim());
        proposalsRepo.save(proposal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", proposal.getId());
        out.put("proposalNo", proposal.getProposalNo());
        out.put("version", version);
        out.put("fileName", doc.getFileName());
        out.put("fileSize", doc.getFileSize());
        out.put("totalCost", money.total);
        log.info("Generated solar proposal {} v{} for lead {} ({} bytes)",
                proposal.getProposalNo(), version, lead.getId(), docx.length);
        return out;
    }

    /** Version list for a proposal (metadata only — never the file bytes). */
    public List<Map<String, Object>> versions(Long proposalId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProposalDocumentRepo.DocumentSummary d : proposalDocumentRepo.findSummaries(proposalId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("version", d.getVersion());
            m.put("fileName", d.getFileName());
            m.put("fileSize", d.getFileSize());
            m.put("generatedAt", d.getGeneratedAt());
            m.put("generatedByName", d.getGeneratedByName());
            m.put("previewable", Boolean.TRUE.equals(d.getPreviewable()));
            out.add(m);
        }
        return out;
    }

    /**
     * One stored version's .docx. @param version null = latest.
     *
     * <p>Deliberately built from scalar projections rather than the entity: the
     * @Basic(LAZY) on the blob columns is a no-op without bytecode enhancement, so
     * loading the entity would drag the PDF along with every .docx download.
     * No @Transactional — there is no session-bound state left to keep open, and
     * these rows are immutable once written.
     */
    public StoredDocument document(Long proposalId, Integer version) throws CustomException {
        ProposalDocumentRepo.DocumentMeta m = requireMeta(proposalId, version);
        byte[] bytes = first(proposalDocumentRepo.findFileDataById(m.getId()));
        return new StoredDocument(m.getFileName(), m.getContentType(), bytes);
    }

    /**
     * One stored version's PDF rendition, for preview. Renders and caches it on
     * first request when the row predates the feature, so nothing needs backfilling.
     *
     * @return the PDF, or {@code null} when this version cannot be given one
     *         (no stored payload, or the re-render failed) — the caller answers 409.
     */
    public StoredDocument pdf(Long proposalId, Integer version) throws CustomException {
        ProposalDocumentRepo.DocumentMeta m = requireMeta(proposalId, version);
        String pdfName = pdfFileName(m.getFileName());

        if (m.getPdfSize() != null && m.getPdfSize() > 0) {
            byte[] cached = first(proposalDocumentRepo.findPdfDataById(m.getId()));
            if (cached != null && cached.length > 0) return new StoredDocument(pdfName, PDF_MIME, cached);
        }

        String payload = first(proposalDocumentRepo.findPayloadById(m.getId()));
        if (payload == null || payload.isBlank()) {
            log.info("Proposal {} v{} has no stored payload; cannot render a preview",
                     proposalId, m.getVersion());
            return null;
        }

        try {
            SolarProposalDocRequest req = mapper.readValue(payload, SolarProposalDocRequest.class);
            // parseDate() falls back to TODAY on a blank value, which would print the
            // wrong quote date on a re-render. Pin it to when the document was made.
            if (nvl(req.getQuoteValidFrom()).isBlank() && m.getGeneratedAt() != null) {
                req.setQuoteValidFrom(m.getGeneratedAt().toLocalDate().toString());
            }
            RenderInputs in = renderInputs(req);
            byte[] bytes = solarProposalPdfService.render(in.tokens(), in.blocks(), in.repeats());
            proposalDocumentRepo.storePdf(m.getId(), bytes, (long) bytes.length, LocalDateTime.now());
            log.info("Back-filled PDF for proposal {} v{} ({} bytes)", proposalId, m.getVersion(), bytes.length);
            return new StoredDocument(pdfName, PDF_MIME, bytes);
        } catch (Exception e) {
            log.error("Could not re-render the PDF for proposal {} v{}", proposalId, m.getVersion(), e);
            return null;
        }
    }

    /**
     * Drop one stored version.
     *
     * <p>The versions are the proposal's history — re-generating appends and
     * never overwrites — so this refuses to empty it: the last one standing can
     * only go with the proposal itself ({@code DELETE /proposals/delete/{id}}).
     *
     * <p>When the version removed was the current one, the proposal record rolls
     * back to the version below it — number, quoted value and title, taken from
     * that version's own stored payload — so the card can never advertise a
     * version whose file is gone.
     *
     * @return what is current afterwards, for the caller's message.
     */
    @Transactional
    public Map<String, Object> deleteVersion(Long proposalId, Integer version) throws CustomException {
        if (version == null) throw new CustomException("No version given to delete");
        ProposalDocumentRepo.DocumentMeta meta = proposalDocumentRepo.findMeta(proposalId, version)
                .orElseThrow(() -> new CustomException("v" + version + " no longer exists on this proposal"));
        if (proposalDocumentRepo.countByProposal(proposalId) <= 1) {
            throw new CustomException("v" + version + " is the only version of this proposal — "
                    + "delete the proposal itself to remove it.");
        }

        proposalDocumentRepo.deleteRowById(meta.getId());
        int latest = proposalDocumentRepo.maxVersion(proposalId);

        ProposalsEntity proposal = proposalsRepo.findById(proposalId).orElse(null);
        if (proposal != null && proposal.getVersion() != null
                && proposal.getVersion().intValue() == version.intValue()) {
            proposal.setVersion(latest);
            Map<String, Object> payload = latestPayload(proposalId);
            if (payload != null) {
                BigDecimal total = decimal(payload.get("totalCost"));
                if (total != null && total.signum() > 0) proposal.setTotalValue(total);
                Object t = payload.get("title");
                if (t != null && !String.valueOf(t).isBlank()) proposal.setTitle(String.valueOf(t).trim());
            }
            proposalsRepo.save(proposal);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", proposalId);
        out.put("deletedVersion", version);
        out.put("currentVersion", latest);
        out.put("remaining", proposalDocumentRepo.countByProposal(proposalId));
        log.info("Deleted proposal {} document v{}; current version is now v{}", proposalId, version, latest);
        return out;
    }

    /** The review payload of whatever version is on top right now. */
    private Map<String, Object> latestPayload(Long proposalId) {
        String json = first(proposalDocumentRepo.findPayloadsByProposal(proposalId, PageRequest.of(0, 1)));
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Could not parse the payload of proposal {}'s latest version: {}", proposalId, e.getMessage());
            return null;
        }
    }

    /** JSON numbers arrive as Integer/Long/Double depending on how they were written. */
    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Resolves a null version to the latest, and 404s a miss. */
    private ProposalDocumentRepo.DocumentMeta requireMeta(Long proposalId, Integer version)
            throws CustomException {
        Integer v = version != null ? version : proposalDocumentRepo.maxVersion(proposalId);
        if (v == null || v <= 0) throw new CustomException("No generated document for this proposal");
        return proposalDocumentRepo.findMeta(proposalId, v)
                .orElseThrow(() -> new CustomException("No generated document for this proposal"));
    }

    private static <T> T first(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** "Client 4 kWp Proposal v2.docx" -> "... v2.pdf". */
    static String pdfFileName(String docxName) {
        if (docxName == null || docxName.isBlank()) return "proposal.pdf";
        return docxName.toLowerCase().endsWith(".docx")
                ? docxName.substring(0, docxName.length() - 5) + ".pdf"
                : docxName + ".pdf";
    }

    /** The render inputs both renditions share. */
    record RenderInputs(Map<String, String> tokens,
                        Map<String, Boolean> blocks,
                        Map<String, List<Map<String, String>>> repeats) { }

    /**
     * Rebuilds the token/block/repeat triple from a request — the same one
     * {@code generate()} produces, so a re-rendered PDF matches the original .docx
     * exactly. Kept here because buildTokens/bomRows and the inclusion rules are
     * private to this class and must stay the single source of truth.
     */
    RenderInputs renderInputs(SolarProposalDocRequest req) {
        Money money = Money.of(req.getBasePrice(), req.getGstPercent(), req.getTotalCost());
        boolean withSubsidy = req.getIncludeSubsidy() != null
                ? req.getIncludeSubsidy() : subsidyEligible(req.getPropertyType());
        boolean withRoi = req.getIncludeRoi() != null
                ? req.getIncludeRoi() : roiAvailable(req.getCapacityKw(), req.getTariffPerUnit());
        return new RenderInputs(
                buildTokens(req, money, withRoi),
                Map.of("SUBSIDY", withSubsidy, "ROI", withRoi),
                Map.of("BOM", bomRows(req)));
    }

    /** Detached copy of a stored document, safe to serve outside the session. */
    public record StoredDocument(String fileName, String contentType, byte[] data) { }

    // ═════════════════════════════════════════════════════════════════════════
    // TOKENS
    // ═════════════════════════════════════════════════════════════════════════

    private Map<String, String> buildTokens(SolarProposalDocRequest req, Money money, boolean withRoi) {
        String capacityLabel = blankToDash(req.getCapacityLabel());
        Map<String, String> t = new LinkedHashMap<>();

        t.put("DOC_TITLE", nvl(req.getTitle()).isBlank()
                ? nvl(req.getClientName()) + " " + capacityLabel + " Proposal"
                : req.getTitle());

        t.put("COVER_TITLE", nvl(req.getCoverTitle()));
        t.put("COVER_SUBTITLE", nvl(req.getCoverSubtitle()));
        t.put("CLIENT_NAME", nvl(req.getClientName()));
        t.put("SITE_LOCATION", nvl(req.getSiteLocation()));
        t.put("CAPACITY_LABEL", capacityLabel);
        t.put("CAPACITY_LINE", nvl(req.getCapacityLine()));

        t.put("WORK_DESC", nvl(req.getWorkDescription()));
        t.put("PRICE_BASE", inr(money.base));
        t.put("GST_PCT", percent(money.gstPercent));
        t.put("GST_AMOUNT", inr(money.gst));
        t.put("GST_AMOUNT_ROUNDED", inr(money.gst));
        t.put("PRICE_TOTAL", inr(money.total));
        t.put("AMOUNT_WORDS", AmountInWords.rupees(money.total));

        LocalDate from = parseDate(req.getQuoteValidFrom());
        int days = req.getQuoteValidDays() != null && req.getQuoteValidDays() > 0
                ? req.getQuoteValidDays() : DEFAULT_QUOTE_VALID_DAYS;
        t.put("QUOTE_VALID_DAYS", String.valueOf(days));
        t.put("QUOTE_VALID_DAY", from.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        t.put("QUOTE_VALID_DATE", from.format(DateTimeFormatter.ofPattern("dd-MM-yy")));

        t.put("SUBSIDY_LINE", "Central Government Subsidy Applicable: " + inr(req.getSubsidyAmount()));
        t.put("SUBSIDY_NOTE_1", defaulted(req.getSubsidyNote1(), SUBSIDY_NOTE_1));
        t.put("SUBSIDY_NOTE_2", defaulted(req.getSubsidyNote2(), SUBSIDY_NOTE_2));
        t.put("SUBSIDY_NOTE_3", defaulted(req.getSubsidyNote3(), SUBSIDY_NOTE_3));

        if (withRoi) {
            Roi roi = Roi.compute(req, money);
            t.put("ROI_TITLE_CAP", capacityLabel);
            t.put("ROI_CAPACITY", capacityLabel);
            t.put("ROI_TARIFF", inr(req.getTariffPerUnit(), 2) + " / Unit");
            t.put("ROI_SPECIFIC_GEN", grouped(roi.specificGeneration) + " Units/kWp/Year");
            t.put("ROI_ANNUAL_GEN", grouped(roi.annualGeneration) + " Units");
            t.put("ROI_ANNUAL_SAVINGS", inr(roi.annualSavings));
            t.put("ROI_MONTHLY_SAVINGS", inr(roi.monthlySavings));
            t.put("ROI_PAYBACK", roi.paybackYears.toPlainString() + " Years");
            t.put("ROI_ANNUAL_ROI", roi.annualRoiPercent.toPlainString() + "%");
            t.put("ROI_LIFE", roi.lifeYears + "+ Years");
            t.put("ROI_LIFETIME_GEN", grouped(roi.lifetimeGeneration) + " Units");
            t.put("ROI_LIFETIME_SAVINGS", inr(roi.lifetimeSavings));
            t.put("ROI_NET_BENEFIT", inr(roi.netLifetimeBenefit));
        }
        return t;
    }

    private List<Map<String, String>> bomRows(SolarProposalDocRequest req) {
        List<Map<String, String>> rows = new ArrayList<>();
        int i = 1;
        for (SolarProposalDocRequest.BomRow b : req.getBomRows()) {
            if (b == null) continue;
            if (nvl(b.getComponent()).isBlank() && nvl(b.getSpecification()).isBlank()) continue;
            Map<String, String> r = new LinkedHashMap<>();
            r.put("SL", String.valueOf(i++));
            r.put("COMPONENT", nvl(b.getComponent()));
            r.put("SPEC", nvl(b.getSpecification()));
            r.put("MAKE", nvl(b.getMake()));
            r.put("QTY", nvl(b.getQuantity()));
            r.put("UNIT", nvl(b.getUnit()));
            rows.add(r);
        }
        return rows;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Pricing + ROI arithmetic
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Base / GST / total kept internally consistent. A supplied total wins and the
     * base is back-computed from it — sales quote round totals, which is why the
     * reference proposals' base prices are not round numbers.
     */
    static final class Money {
        final BigDecimal base;
        final BigDecimal gst;
        final BigDecimal total;
        final BigDecimal gstPercent;

        private Money(BigDecimal base, BigDecimal gst, BigDecimal total, BigDecimal gstPercent) {
            this.base = base; this.gst = gst; this.total = total; this.gstPercent = gstPercent;
        }

        static Money of(BigDecimal base, BigDecimal gstPercent, BigDecimal total) {
            BigDecimal pct = gstPercent == null ? DEFAULT_GST_PERCENT : gstPercent;
            BigDecimal factor = BigDecimal.ONE.add(pct.movePointLeft(2));
            if (total != null && total.signum() > 0) {
                BigDecimal t = total.setScale(0, RoundingMode.HALF_UP);
                BigDecimal b = t.divide(factor, 0, RoundingMode.HALF_UP);
                return new Money(b, t.subtract(b), t, pct);
            }
            BigDecimal b = base == null ? BigDecimal.ZERO : base.setScale(0, RoundingMode.HALF_UP);
            BigDecimal t = b.multiply(factor).setScale(0, RoundingMode.HALF_UP);
            return new Money(b, t.subtract(b), t, pct);
        }
    }

    /**
     * Every derived ROI row. Nothing here is stored anywhere in the CRM — each
     * value is computed from the capacity, the tariff entered from the Site Visit
     * tab, and the two declared assumptions.
     */
    static final class Roi {
        BigDecimal specificGeneration;
        BigDecimal annualGeneration;
        BigDecimal annualSavings;
        BigDecimal monthlySavings;
        BigDecimal paybackYears;
        BigDecimal annualRoiPercent;
        int lifeYears;
        BigDecimal lifetimeGeneration;
        BigDecimal lifetimeSavings;
        BigDecimal netLifetimeBenefit;

        static Roi compute(SolarProposalDocRequest req, Money money) {
            Roi r = new Roi();
            BigDecimal capacity = req.getCapacityKw();
            BigDecimal tariff = req.getTariffPerUnit();
            r.specificGeneration = req.getSpecificGeneration() != null && req.getSpecificGeneration().signum() > 0
                    ? req.getSpecificGeneration() : DEFAULT_SPECIFIC_GENERATION;
            r.lifeYears = req.getSystemLifeYears() != null && req.getSystemLifeYears() > 0
                    ? req.getSystemLifeYears() : DEFAULT_SYSTEM_LIFE_YEARS;

            r.annualGeneration = capacity.multiply(r.specificGeneration).setScale(0, RoundingMode.HALF_UP);
            r.annualSavings = r.annualGeneration.multiply(tariff).setScale(0, RoundingMode.HALF_UP);
            r.monthlySavings = r.annualSavings.divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
            r.paybackYears = r.annualSavings.signum() == 0 ? BigDecimal.ZERO
                    : money.total.divide(r.annualSavings, 1, RoundingMode.HALF_UP);
            r.annualRoiPercent = money.total.signum() == 0 ? BigDecimal.ZERO
                    : r.annualSavings.multiply(BigDecimal.valueOf(100))
                        .divide(money.total, 2, RoundingMode.HALF_UP);
            r.lifetimeGeneration = r.annualGeneration.multiply(BigDecimal.valueOf(r.lifeYears));
            r.lifetimeSavings = r.annualSavings.multiply(BigDecimal.valueOf(r.lifeYears));
            r.netLifetimeBenefit = r.lifetimeSavings.subtract(money.total);
            return r;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Lead-tab resolution helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Whether this lead/proposal takes the solar document path. Matched on the
     * sub-group ({@code Solar_Rooftop}, {@code Solar_ground_mounted},
     * {@code Solar_carports}, {@code Solar Wind}); a literal "Solar" group is
     * still honoured in case the taxonomy is ever flattened.
     */
    public static boolean isSolar(String groupName, String subGroupName) {
        String sub = subGroupName == null ? "" : subGroupName.trim().toLowerCase();
        String grp = groupName == null ? "" : groupName.trim();
        return sub.startsWith(SOLAR_SUBGROUP_PREFIX) || "Solar".equalsIgnoreCase(grp);
    }

    private void requireSolar(LeadsEntity lead) throws CustomException {
        if (!isSolar(lead.getGroupName(), lead.getSubGroupName())) {
            throw new CustomException(
                "Proposal generation from the lead is available for Solar sub-groups only "
              + "(this lead is " + nvl(lead.getGroupName()) + " / " + nvl(lead.getSubGroupName()) + ")");
        }
    }

    /** Technical Scope's capacity wins; the lead's own capacity is the fallback. */
    private BigDecimal resolveCapacityKw(LeadsEntity lead, LeadScopeEntity scope) {
        if (scope != null && scope.getSystemCapacity() != null) {
            BigDecimal v = firstNumber(scope.getSystemCapacity());
            if (v != null && v.signum() > 0) return v;
        }
        CapacityUtil.CapacityInfo cap =
                CapacityUtil.parse(lead.getCapacity(), lead.getCapacityUnit(), lead.getSubGroupName());
        return cap != null && cap.isUsable() ? cap.scaleBase() : null;
    }

    private String capacityLabel(BigDecimal capacityKw, LeadScopeEntity scope) {
        if (capacityKw != null && capacityKw.signum() > 0) return trimNumber(capacityKw) + " kWp";
        if (scope != null && scope.getSystemCapacity() != null && !scope.getSystemCapacity().isBlank()) {
            return scope.getSystemCapacity().trim();
        }
        return "";
    }

    /**
     * The lead's property type, or {@code null} when nothing is recorded.
     *
     * <p>Null is deliberate: it means "unknown", not "Residential". Guessing here is
     * how a commercial lead used to pick up a residential subsidy — the telecaller
     * toggle clears {@code tc_property_type} when the active option is re-clicked, and
     * a blank field used to read as Residential. The preparer picks the type in the
     * review step instead.
     */
    private String resolvePropertyType(LeadsEntity lead, LeadScopeEntity scope) {
        if (lead.getTcPropertyType() != null && !lead.getTcPropertyType().isBlank()) {
            return lead.getTcPropertyType().trim();
        }
        String pt = scope == null ? null : scope.getProjectType();
        for (String known : PROPERTY_TYPES) {
            if (pt != null && pt.toLowerCase().contains(known.toLowerCase())) return known;
        }
        return null;
    }

    /**
     * Whether the MNRE PM Surya Ghar rooftop subsidy can apply. Residential rooftops
     * only — commercial, industrial and the rest are not eligible, and an unknown type
     * is treated as not eligible because a subsidy wrongly printed on a commercial
     * quote is far more costly than one the preparer has to tick on.
     */
    static boolean subsidyEligible(String propertyType) {
        return propertyType != null && propertyType.trim().equalsIgnoreCase("Residential");
    }

    /**
     * Whether the ROI Analysis section can be built. Property type does not enter into
     * it: {@link Roi#compute} is capacity × tariff × generation over the system life,
     * and a payback table is at least as persuasive to a commercial buyer as to a
     * homeowner. The only question is whether the two inputs exist — there is no
     * default tariff, because a made-up ₹/unit would land in a client-facing quote.
     */
    static boolean roiAvailable(BigDecimal capacityKw, BigDecimal tariffPerUnit) {
        return capacityKw != null && capacityKw.signum() > 0
                && tariffPerUnit != null && tariffPerUnit.signum() > 0;
    }

    private String resolveLocation(LeadsEntity lead, LeadScopeEntity scope, SiteVisitEntity visit) {
        if (scope != null && notBlank(scope.getSiteLocation())) return scope.getSiteLocation().trim();
        if (visit != null && notBlank(visit.getSiteAddress())) return visit.getSiteAddress().trim();
        if (notBlank(lead.getTcLocation())) return lead.getTcLocation().trim();
        List<String> parts = new ArrayList<>();
        for (String p : new String[] { lead.getCity(), lead.getDistrict(), lead.getState() }) {
            if (notBlank(p) && !parts.contains(p.trim())) parts.add(p.trim());
        }
        return String.join(", ", parts);
    }

    /** The Budget Estimation tab's proposal price (cost + margin). */
    private BigDecimal budgetPrice(Long leadId) {
        try {
            Object v = leadScopeService.getEstimationSummary(leadId).get("proposalPrice");
            if (v instanceof BigDecimal bd && bd.signum() > 0) return bd.setScale(0, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("Could not read estimation summary for lead {}: {}", leadId, e.getMessage());
        }
        return null;
    }

    private BigDecimal defaultSubsidy(BigDecimal capacityKw) {
        if (capacityKw == null || capacityKw.signum() <= 0) return null;
        BigDecimal cap = capacityKw.min(BigDecimal.valueOf(3));
        BigDecimal firstTwo = cap.min(BigDecimal.valueOf(2)).multiply(BigDecimal.valueOf(30000));
        BigDecimal third = cap.subtract(BigDecimal.valueOf(2)).max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(18000));
        return firstTwo.add(third).min(SUBSIDY_CAP).setScale(0, RoundingMode.HALF_UP);
    }

    private ProposalsEntity resolveProposal(SolarProposalDocRequest req, LeadsEntity lead,
                                            Long userId, BigDecimal total) throws CustomException {
        if (req.getProposalId() != null) {
            ProposalsEntity p = proposalsRepo.findById(req.getProposalId())
                    .orElseThrow(() -> new CustomException("Proposal not found"));
            if (p.getDeletedAt() != null) throw new CustomException("Proposal has been deleted");
            return p;
        }
        ProposalsEntity p = new ProposalsEntity();
        p.setLeadId(lead.getId());
        p.setCustomerId(lead.getCustomerId());
        p.setTitle(notBlank(req.getTitle()) ? req.getTitle().trim()
                : nvl(lead.getName()) + " — Rooftop Solar Proposal");
        p.setDescription("Generated from the lead's Technical Scope, BOM, Budget and Site Visit tabs.");
        p.setPreparedBy(userId);
        p.setStatus("Draft");
        p.setTotalValue(total);
        p.setGroupName(lead.getGroupName());
        p.setSubGroupName(lead.getSubGroupName());
        ProposalsEntity saved = proposalsRepo.save(p);
        saved.setProposalNo(String.format("PROP-%d-%06d", java.time.LocalDate.now().getYear(), saved.getId()));
        return proposalsRepo.save(saved);
    }

    private String fileName(SolarProposalDocRequest req, ProposalsEntity proposal, int version) {
        String base = notBlank(req.getClientName()) ? req.getClientName() : proposal.getProposalNo();
        String cap = notBlank(req.getCapacityLabel()) ? " " + req.getCapacityLabel() : "";
        return (base + cap + " Proposal v" + version + ".docx").replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private String userName(Long userId) {
        if (userId == null) return null;
        return usersRepo.findById(userId).map(UsersEntity::getName).orElse(null);
    }

    private byte[] template() throws IOException {
        byte[] cached = templateBytes;
        if (cached != null) return cached;
        synchronized (this) {
            if (templateBytes == null) {
                try (InputStream in = new ClassPathResource(TEMPLATE).getInputStream()) {
                    templateBytes = in.readAllBytes();
                }
                log.info("Loaded solar proposal skeleton ({} bytes)", templateBytes.length);
            }
            return templateBytes;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Formatting
    // ═════════════════════════════════════════════════════════════════════════

    /** Indian digit grouping: 253461 → "2,53,461". */
    static String grouped(BigDecimal v) {
        if (v == null) return "";
        BigDecimal abs = v.abs().setScale(0, RoundingMode.HALF_UP);
        String digits = abs.toPlainString();
        StringBuilder sb = new StringBuilder();
        int len = digits.length();
        if (len <= 3) {
            sb.append(digits);
        } else {
            String last3 = digits.substring(len - 3);
            String rest = digits.substring(0, len - 3);
            StringBuilder head = new StringBuilder();
            while (rest.length() > 2) {
                head.insert(0, "," + rest.substring(rest.length() - 2));
                rest = rest.substring(0, rest.length() - 2);
            }
            sb.append(rest).append(head).append(',').append(last3);
        }
        return (v.signum() < 0 ? "-" : "") + sb;
    }

    static String inr(BigDecimal v) {
        if (v == null) return "";
        return "₹" + grouped(v);
    }

    /** Keeps decimals — used for the ₹/unit tariff. */
    static String inr(BigDecimal v, int scale) {
        if (v == null) return "";
        BigDecimal r = v.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
        return "₹" + (r.scale() <= 0 ? grouped(r) : r.toPlainString());
    }

    static String percent(BigDecimal v) {
        if (v == null) return "";
        return v.stripTrailingZeros().toPlainString() + "%";
    }

    /** "4.00" → "4", "4.50" → "4.5" — capacities and quantities read better bare. */
    static String trimNumber(BigDecimal v) {
        if (v == null) return "";
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    /** First number in a free-text field, e.g. "₹9.25 per unit" → 9.25. */
    static BigDecimal firstNumber(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:[.,]\\d+)?)").matcher(s.replace(",", ""));
        if (!m.find()) return null;
        try { return new BigDecimal(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return LocalDate.now();
        try { return LocalDate.parse(iso.substring(0, 10)); } catch (Exception e) { return LocalDate.now(); }
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String blankToDash(String s) { return notBlank(s) ? s.trim() : ""; }
    private static String defaulted(String s, String fallback) { return notBlank(s) ? s.trim() : fallback; }
}
