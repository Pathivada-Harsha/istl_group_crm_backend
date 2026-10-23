package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProposalDocumentEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProposalDocumentRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GenericProposalDocRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Generates the standard business proposal for every lead the solar generator does
 * not serve - which is every lead in every organization that is not selling solar.
 *
 * <p>Before this existed, a non-solar organization could only author proposal text on
 * the Proposals page and print an unstored PDF. That had a second consequence beyond
 * the missing document: {@link ProposalSource} treats a row in
 * {@code proposal_documents} as the signal that a proposal was built inside the CRM,
 * so a proposal with no generated document could never back an order book. This
 * service stores its output exactly where the solar one does, so such proposals are
 * now genuinely system-generated and the order-book gate opens with no flag.
 *
 * <p>The deliverable here is a PDF, not a .docx. The solar path fills a signed-off
 * Word skeleton; there is no equivalent skeleton for "any organization, any
 * industry", and a per-organization one would have to be authored and maintained per
 * tenant. {@link GenericProposalPdfService} lays the document out instead, which is
 * also why a render failure is fatal here where it is best-effort for solar - there
 * is no other artifact to fall back on.
 *
 * <p>Branding is fixed (Sesola name and the bundled logo) - see {@link #branding()}.
 */
@Service
@Slf4j
public class GenericProposalDocService {

    private static final String PDF_MIME = "application/pdf";

    /**
     * The ordinary Indian GST rate. Deliberately not shared with the solar service's
     * 8.9%, which is the composite rate for a rooftop solar plant - a rate that is
     * correct for one product line is not a platform default.
     */
    private static final BigDecimal DEFAULT_GST_PERCENT = new BigDecimal("18");
    private static final int DEFAULT_VALIDITY_DAYS = 15;

    @Autowired private LeadsRepo leadsRepo;
    @Autowired private LeadScopeRepo leadScopeRepo;
    @Autowired private LeadScopeItemRepo leadScopeItemRepo;
    @Autowired private LeadBomRepo leadBomRepo;
    @Autowired private ProposalsRepo proposalsRepo;
    @Autowired private ProposalDocumentRepo proposalDocumentRepo;
    @Autowired private UsersRepo usersRepo;
    @Autowired private LeadScopeService leadScopeService;
    @Autowired private GenericProposalPdfService genericProposalPdfService;

    private final ObjectMapper mapper = new ObjectMapper();

    // =========================================================================
    // PREFILL - what the review step opens with
    // =========================================================================

    /**
     * Defaults for every variable region, drawn from the lead's tabs and from the
     * proposal record's own text fields. Nothing is invented: a field with no stored
     * source comes back blank and the review step shows it.
     */
    public Map<String, Object> prefill(Long leadId, Long proposalId) throws CustomException {
        LeadsEntity lead = leadsRepo.findById(leadId)
                .orElseThrow(() -> new CustomException("Lead not found"));
        requireNonSolar(lead);

        // A previous generation wins: the preparer's edits should not be lost.
        // Payload only - the stored file must not be loaded here.
        Pageable one = PageRequest.of(0, 1);
        List<String> payloads = proposalId != null
                ? proposalDocumentRepo.findPayloadsByProposal(proposalId, one)
                : proposalDocumentRepo.findPayloadsByLead(leadId, one);
        String previousPayload = payloads.isEmpty() ? null : payloads.get(0);
        int lastVersion = proposalId == null ? 0 : proposalDocumentRepo.maxVersion(proposalId);

        LeadScopeEntity scope = leadScopeRepo.findByLeadIdAndDeletedAtIsNull(leadId).orElse(null);
        List<LeadScopeItemEntity> scopeItems =
                leadScopeItemRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        List<LeadBomEntity> bom = leadBomRepo.findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(leadId);
        ProposalsEntity proposal = proposalId == null ? null
                : proposalsRepo.findById(proposalId).orElse(null);

        BigDecimal basePrice = budgetPrice(leadId);
        Branding brand = branding();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leadId", leadId);
        out.put("proposalId", proposalId);
        out.put("leadCode", lead.getLeadCode());
        out.put("groupName", lead.getGroupName());
        out.put("subGroupName", lead.getSubGroupName());
        // A new proposal still prefills from the lead's last document, but it is v1.
        out.put("regeneration", proposalId != null && lastVersion > 0);
        out.put("lastVersion", lastVersion);

        // Branding preview only - never the logo bytes, which the document alone needs.
        out.put("orgName", brand.name());
        out.put("hasLogo", brand.logo() != null);

        out.put("clientName", nvl(lead.getName()));
        out.put("clientAddress", clientAddress(lead));
        out.put("siteLocation", resolveLocation(lead, scope));
        out.put("title", proposal != null && notBlank(proposal.getTitle())
                ? proposal.getTitle()
                : (nvl(lead.getName()) + " - " + defaulted(subject(lead, scope), "Proposal")).trim());

        // The proposal record's own text fields, authored on the Proposals page.
        out.put("description", proposal == null ? "" : nvl(proposal.getDescription()));
        out.put("aboutUs", proposal == null ? "" : nvl(proposal.getAboutUs()));
        out.put("aboutSystem", proposal == null ? "" : nvl(proposal.getAboutSystem()));
        out.put("paymentTerms", proposal == null ? "" : nvl(proposal.getPaymentTerms()));
        out.put("defectLiabilityPeriod", proposal == null ? "" : nvl(proposal.getDefectLiabilityPeriod()));
        out.put("warrantyNotes", "");
        out.put("textSource", proposal == null
                ? "No proposal record yet - the narrative sections stay empty until you write them."
                : "Proposal record - About Us, About the System, Payment Terms and Defect Liability.");

        out.put("workDescription", defaulted(scope == null ? null : scope.getScopeOfWork(),
                subject(lead, scope)));
        out.put("basePrice", basePrice);
        out.put("gstPercent", DEFAULT_GST_PERCENT);
        out.put("totalCost", basePrice == null ? null
                : basePrice.multiply(BigDecimal.ONE.add(DEFAULT_GST_PERCENT.movePointLeft(2)))
                        .setScale(0, RoundingMode.HALF_UP));
        out.put("budgetSource", basePrice == null
                ? "No Budget Estimation recorded for this lead yet."
                : "Budget Estimation tab - proposal price (cost + margin).");

        out.put("validityDays", DEFAULT_VALIDITY_DAYS);
        out.put("quoteValidFrom", LocalDate.now().toString());

        // Scope of work: the lead's Technical Scope lines, exactly as entered.
        List<Map<String, Object>> scopeRows = new ArrayList<>();
        for (LeadScopeItemEntity s : scopeItems) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("activity", nvl(s.getActivity()));
            r.put("specification", nvl(s.getSpecification()));
            r.put("quantity", SolarProposalDocService.trimNumber(s.getQuantity()));
            r.put("unit", nvl(s.getUnit()));
            r.put("notes", nvl(s.getNotes()));
            scopeRows.add(r);
        }
        out.put("scopeRows", scopeRows);
        out.put("scopeSource", scopeRows.isEmpty()
                ? "No scope lines on this lead - add them on the Technical Scope tab first."
                : scopeRows.size() + " line(s) from the lead's Technical Scope tab.");

        // Scope of supply: the lead's BOM, without rates - the offer is one lump sum.
        List<Map<String, Object>> bomRows = new ArrayList<>();
        for (LeadBomEntity b : bom) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("component", nvl(b.getItemName()));
            r.put("specification", nvl(b.getSpecification()));
            r.put("make", nvl(b.getMake()));
            r.put("quantity", SolarProposalDocService.trimNumber(b.getQuantity()));
            r.put("unit", nvl(b.getUnit()));
            bomRows.add(r);
        }
        out.put("bomRows", bomRows);
        out.put("bomSource", bomRows.isEmpty()
                ? "No BOM lines on this lead - add them on the BOM tab first."
                : bomRows.size() + " line(s) from the lead's BOM tab.");

        if (previousPayload != null) {
            try {
                out.put("previous", mapper.readValue(previousPayload, Map.class));
            } catch (Exception e) {
                log.warn("Could not parse previous proposal payload for lead {}: {}", leadId, e.getMessage());
            }
        }
        return out;
    }

    // =========================================================================
    // GENERATE
    // =========================================================================

    /**
     * Renders the document and stores it as the next version against the proposal
     * record, creating that record on the first run.
     */
    @Transactional
    public Map<String, Object> generate(GenericProposalDocRequest req, Long userId) throws CustomException {
        if (req.getLeadId() == null) throw new CustomException("leadId is required");
        LeadsEntity lead = leadsRepo.findById(req.getLeadId())
                .orElseThrow(() -> new CustomException("Lead not found"));
        requireNonSolar(lead);

        if (!notBlank(req.getTitle()) && !notBlank(req.getClientName())) {
            throw new CustomException("The proposal needs a title or a client name");
        }
        boolean hasScope = req.getScopeRows() != null && !req.getScopeRows().isEmpty();
        boolean hasBom = req.getBomRows() != null && !req.getBomRows().isEmpty();
        if (!hasScope && !hasBom) {
            throw new CustomException("The proposal needs at least one scope or Bill of Material line - "
                    + "add them on the lead's Technical Scope or BOM tab.");
        }

        // Reuses the solar service's arithmetic - base/GST/total kept consistent with a
        // supplied total winning - which carries no solar semantics. The percentage is
        // always passed explicitly so that class's 8.9% default can never apply here.
        BigDecimal pct = req.getGstPercent() != null ? req.getGstPercent() : DEFAULT_GST_PERCENT;
        SolarProposalDocService.Money money =
                SolarProposalDocService.Money.of(req.getBasePrice(), pct, req.getTotalCost());
        if (money.total.signum() <= 0) {
            throw new CustomException("Enter a price - the Budget Estimation tab has no value for this lead.");
        }

        ProposalsEntity proposal = resolveProposal(req, lead, userId, money.total);
        int version = proposalDocumentRepo.maxVersion(proposal.getId()) + 1;

        Branding brand = branding();
        // The PDF is the deliverable, not a preview rendition, so a failure here must
        // abort the generation rather than leave a version row pointing at nothing.
        byte[] pdf = genericProposalPdfService.render(req, money,
                new GenericProposalPdfService.Branding(brand.name(), brand.logo()),
                new GenericProposalPdfService.DocumentRef(
                        proposal.getProposalNo(), version, LocalDate.now()),
                null);   // no signature image: the signing line is left blank for a hand signature

        ProposalDocumentEntity doc = new ProposalDocumentEntity();
        doc.setProposalId(proposal.getId());
        doc.setLeadId(lead.getId());
        doc.setVersion(version);
        doc.setFileName(fileName(req, proposal, version));
        doc.setContentType(PDF_MIME);
        doc.setFileSize((long) pdf.length);
        doc.setFileData(pdf);
        // pdf_data is left null on purpose. For a .docx row it caches a separate
        // rendition; here file_data already IS the PDF, and storing it twice would
        // double the row for nothing - pdf() below serves the deliverable directly.
        doc.setGeneratedBy(userId);
        doc.setGeneratedByName(userName(userId));
        try {
            doc.setPayload(mapper.writeValueAsString(req));
        } catch (Exception e) {
            log.warn("Could not serialize generic proposal payload: {}", e.getMessage());
        }
        proposalDocumentRepo.save(doc);

        // Keep the tracked record in step: its version mirrors the latest file.
        proposal.setVersion(version);
        proposal.setExactTotal(money.exactTotal);
        proposal.setRoundOff(money.roundOff());
        proposal.setTotalValue(money.total);
        if (notBlank(req.getTitle())) proposal.setTitle(req.getTitle().trim());
        proposalsRepo.save(proposal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", proposal.getId());
        out.put("proposalNo", proposal.getProposalNo());
        out.put("version", version);
        out.put("fileName", doc.getFileName());
        out.put("fileSize", doc.getFileSize());
        out.put("contentType", PDF_MIME);
        out.put("totalCost", money.total);
        log.info("Generated proposal {} v{} for lead {} ({} bytes)",
                proposal.getProposalNo(), version, lead.getId(), pdf.length);
        return out;
    }

    /**
     * One stored version's PDF, for preview and download.
     *
     * <p>Unlike the solar path there is nothing to re-render: {@code file_data} is
     * already the PDF. The stored payload is only consulted if the bytes are somehow
     * missing, in which case the document is rebuilt from it.
     *
     * @param version null = latest
     * @return the PDF, or {@code null} when this version cannot produce one - the
     *         caller answers 409.
     */
    public SolarProposalDocService.StoredDocument pdf(Long proposalId, Integer version)
            throws CustomException {
        ProposalDocumentRepo.DocumentMeta m = requireMeta(proposalId, version);

        byte[] bytes = first(proposalDocumentRepo.findFileDataById(m.getId()));
        if (bytes != null && bytes.length > 0) {
            return new SolarProposalDocService.StoredDocument(m.getFileName(), PDF_MIME, bytes);
        }

        byte[] cached = first(proposalDocumentRepo.findPdfDataById(m.getId()));
        if (cached != null && cached.length > 0) {
            return new SolarProposalDocService.StoredDocument(m.getFileName(), PDF_MIME, cached);
        }

        String payload = first(proposalDocumentRepo.findPayloadById(m.getId()));
        if (payload == null || payload.isBlank()) {
            log.info("Proposal {} v{} has no stored bytes or payload; cannot serve a PDF",
                     proposalId, m.getVersion());
            return null;
        }
        try {
            GenericProposalDocRequest req = mapper.readValue(payload, GenericProposalDocRequest.class);
            BigDecimal pct = req.getGstPercent() != null ? req.getGstPercent() : DEFAULT_GST_PERCENT;
            SolarProposalDocService.Money money =
                    SolarProposalDocService.Money.of(req.getBasePrice(), pct, req.getTotalCost());
            Branding brand = branding();
            LocalDate issued = m.getGeneratedAt() != null ? m.getGeneratedAt().toLocalDate() : LocalDate.now();
            String proposalNo = proposalsRepo.findById(proposalId)
                    .map(ProposalsEntity::getProposalNo).orElse("");
            byte[] rendered = genericProposalPdfService.render(req, money,
                    new GenericProposalPdfService.Branding(brand.name(), brand.logo()),
                    new GenericProposalPdfService.DocumentRef(proposalNo, m.getVersion(), issued),
                    null);
            proposalDocumentRepo.storePdf(m.getId(), rendered, (long) rendered.length, LocalDateTime.now());
            log.info("Re-rendered the PDF for proposal {} v{} ({} bytes)",
                     proposalId, m.getVersion(), rendered.length);
            return new SolarProposalDocService.StoredDocument(m.getFileName(), PDF_MIME, rendered);
        } catch (Exception e) {
            log.error("Could not re-render the PDF for proposal {} v{}", proposalId, m.getVersion(), e);
            return null;
        }
    }

    // =========================================================================
    // Organization branding
    // =========================================================================

    /** The rendering organization's name and logo, as resolved from the session. */
    private record Branding(String name, byte[] logo) { }

    /** Single-tenant CRM: the header name is fixed and the logo is the bundled Sesola mark. */
    private static final String COMPANY_NAME = "Sesola Power Projects Private Limited";
    private static final String LOGO_ASSET = "proposal-assets/logo.png";

    private byte[] logoBytes;

    private Branding branding() {
        if (logoBytes == null) {
            try (java.io.InputStream in =
                         new org.springframework.core.io.ClassPathResource(LOGO_ASSET).getInputStream()) {
                logoBytes = in.readAllBytes();
            } catch (Exception e) {
                log.warn("Could not load {}: {}", LOGO_ASSET, e.getMessage());
            }
        }
        return new Branding(COMPANY_NAME, logoBytes);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * The mirror of {@code SolarProposalDocService.requireSolar}: this generator takes
     * every lead that one refuses. The two predicates are one expression, so a lead can
     * never fall between them or qualify for both.
     */
    private void requireNonSolar(LeadsEntity lead) throws CustomException {
        if (SolarProposalDocService.isSolar(lead.getGroupName(), lead.getSubGroupName())) {
            throw new CustomException(
                "Solar leads use the solar proposal generator - open the lead and use "
              + "\"Generate Proposal\" there instead.");
        }
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

    /** A one-line subject for the document, from whatever the lead actually records. */
    private String subject(LeadsEntity lead, LeadScopeEntity scope) {
        if (scope != null && notBlank(scope.getProjectType())) return scope.getProjectType().trim();
        if (notBlank(lead.getSubGroupName())) return lead.getSubGroupName().trim().replace('_', ' ');
        if (notBlank(lead.getGroupName())) return lead.getGroupName().trim();
        return "";
    }

    private String clientAddress(LeadsEntity lead) {
        List<String> parts = new ArrayList<>();
        // The lead has no address column of its own; the telecaller's location plus the
        // administrative fields are what it actually records.
        for (String p : new String[] { lead.getTcLocation(), lead.getCity(), lead.getDistrict(),
                                       lead.getState(), lead.getPincode() }) {
            if (notBlank(p) && !parts.contains(p.trim())) parts.add(p.trim());
        }
        return String.join(", ", parts);
    }

    private String resolveLocation(LeadsEntity lead, LeadScopeEntity scope) {
        if (scope != null && notBlank(scope.getSiteLocation())) return scope.getSiteLocation().trim();
        if (notBlank(lead.getTcLocation())) return lead.getTcLocation().trim();
        List<String> parts = new ArrayList<>();
        for (String p : new String[] { lead.getCity(), lead.getDistrict(), lead.getState() }) {
            if (notBlank(p) && !parts.contains(p.trim())) parts.add(p.trim());
        }
        return String.join(", ", parts);
    }

    private ProposalsEntity resolveProposal(GenericProposalDocRequest req, LeadsEntity lead,
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
                : nvl(lead.getName()) + " - Proposal");
        p.setDescription(notBlank(req.getDescription()) ? req.getDescription().trim()
                : "Generated from the lead's Technical Scope, BOM and Budget tabs.");
        p.setPreparedBy(userId);
        p.setStatus("Draft");
        p.setTotalValue(total);
        p.setGroupName(lead.getGroupName());
        p.setSubGroupName(lead.getSubGroupName());
        // companyName is left at the entity's default and is never read by this document.
        ProposalsEntity saved = proposalsRepo.save(p);
        saved.setProposalNo(String.format("PROP-%d-%06d", LocalDate.now().getYear(), saved.getId()));
        return proposalsRepo.save(saved);
    }

    private String fileName(GenericProposalDocRequest req, ProposalsEntity proposal, int version) {
        String base = notBlank(req.getClientName()) ? req.getClientName() : proposal.getProposalNo();
        return (base + " Proposal v" + version + ".pdf").replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private String userName(Long userId) {
        if (userId == null) return null;
        return usersRepo.findById(userId).map(UsersEntity::getName).orElse(null);
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

    private static String nvl(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String defaulted(String s, String fallback) { return notBlank(s) ? s.trim() : fallback; }
}
