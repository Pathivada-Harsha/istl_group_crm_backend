package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.TenderEntity;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelImport;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelOptions;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelReader;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Evidence;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelWriter;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderText;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderTextCleaner;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderWrapper;

import jakarta.servlet.http.HttpSession;

/**
 * The Excel round trip for tenders: a template to hand to an LLM with the
 * tender PDF, and an import that turns the filled file into a review, with
 * every value checked against that PDF.
 *
 * <p>Nothing here writes to the database. The review modal applies what the
 * user chooses through the ordinary create/update, which is also where the
 * import is recorded in the tender's history.
 *
 * <p>The PDF's text is kept on the user's HTTP session (a few at most) under
 * the import's id, so that values edited in the review are checked against the
 * same document without uploading it again. It goes when the session does.
 */
@Service
public class TenderExcelService {

    static final String SESSION_KEY = "TENDER_XLSX_PDFS";
    /** Imports a user may have open in the same session (tabs, retries). */
    static final int MAX_HELD = 3;

    @Autowired
    private TenderService tenderService;

    /** The PDF an import is checked against, as held between import and re-checks. */
    record HeldPdf(String name, TenderText text, boolean scanned) {}

    /**
     * The session's held PDFs, oldest first. Deliberately not Serializable:
     * Tomcat skips such attributes when it persists sessions on shutdown, and
     * the text is cheap to rebuild by importing again.
     */
    static final class Held {
        final LinkedHashMap<String, HeldPdf> byImport = new LinkedHashMap<>();
    }

    public byte[] template(TenderWrapper prefill, TenderExcelOptions options) {
        return TenderExcelWriter.write(prefill, options);
    }

    /**
     * @param pdf       the tender PDF uploaded with the Excel
     * @param tenderId  an existing tender whose stored PDF is used when no PDF is uploaded
     */
    public TenderExcelImport.Result importFile(MultipartFile file, MultipartFile pdf, Long tenderId,
                                               TenderExcelOptions options, HttpSession session) throws CustomException {
        if (file == null || file.isEmpty()) throw new CustomException("No file provided");
        if (file.getSize() > TenderExcelReader.MAX_BYTES) {
            throw new CustomException("The file is larger than " + (TenderExcelReader.MAX_BYTES / (1024 * 1024)) + " MB.");
        }
        HeldPdf held = loadPdf(pdf, tenderId);
        try {
            TenderExcelReader.Workbook wb = TenderExcelReader.read(file.getBytes(), file.getOriginalFilename());
            String importId = UUID.randomUUID().toString();
            hold(session, importId, held);
            return TenderExcelImport.evaluate(wb, options, file.getOriginalFilename(), evidence(held, wb.version()))
                    .withPdf(importId, status(held, wb.version()), held.name(), note(held, wb.version()));
        } catch (TenderExcelReader.Rejected e) {
            throw new CustomException(e.getMessage());
        } catch (IOException e) {
            throw new CustomException("Could not read the file: " + e.getMessage());
        }
    }

    /**
     * Re-check values edited in the review, by the same rules as the import and
     * against the same PDF, when the session still holds it.
     */
    public TenderExcelImport.Result validate(Map<String, String> fields,
                                             Map<String, String> fieldSources,
                                             Map<String, List<Map<String, String>>> tables,
                                             TenderExcelOptions options,
                                             String version, String importId, HttpSession session) {
        String v = version == null || !TenderExcelSchema.ACCEPTED.contains(version) ? TenderExcelSchema.VERSION : version;
        HeldPdf held = held(session, importId);
        Evidence ev = held == null
                ? Evidence.unavailable("The PDF is no longer held for this review (the session ended or too many "
                        + "imports were opened) — import the files again to check these values.")
                : evidence(held, v);
        TenderExcelImport.Result r = TenderExcelImport.evaluate(
                TenderExcelImport.fromEdits(fields, fieldSources, tables), options, v, null, List.of(), ev);
        return held == null ? r.withPdf(importId, "expired", null, ev.reason())
                            : r.withPdf(importId, status(held, v), held.name(), note(held, v));
    }

    // ── the PDF ──────────────────────────────────────────────────────────────

    private HeldPdf loadPdf(MultipartFile pdf, Long tenderId) throws CustomException {
        byte[] bytes;
        String name;
        if (pdf != null && !pdf.isEmpty()) {
            TenderService.validatePdf(pdf);
            try {
                bytes = pdf.getBytes();
            } catch (IOException e) {
                throw new CustomException("Could not read the PDF: " + e.getMessage());
            }
            name = pdf.getOriginalFilename();
        } else if (tenderId != null) {
            TenderEntity t;
            try {
                t = tenderService.getSourcePdfEntity(tenderId);
            } catch (CustomException e) {
                throw new CustomException("This tender has no stored PDF. Upload the tender PDF together with the Excel.");
            }
            bytes = t.getSourcePdfData();
            name = t.getSourcePdfName();
        } else {
            throw new CustomException("Upload the tender PDF together with the Excel — every value is checked against it.");
        }
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF")) {
            throw new CustomException("The tender file is not a valid PDF.");
        }
        try {
            TenderText text = TenderPdfExtractor.load(bytes);
            boolean scanned = text.charCount() < TenderService.MIN_TEXT_LAYER_CHARS;
            return new HeldPdf(name, scanned ? text : TenderTextCleaner.clean(text), scanned);
        } catch (IOException | RuntimeException e) {
            throw new CustomException("Could not read the PDF: " + e.getMessage());
        }
    }

    private static Evidence evidence(HeldPdf held, String version) {
        if (!TenderExcelSchema.hasSourceText(version)) {
            return Evidence.unavailable("This file uses the older template (" + version + "), which has no "
                    + "Source Text column, so values could not be checked against the PDF.");
        }
        if (held.scanned()) {
            return Evidence.unavailable("The PDF has no text layer (it is a scan), so values could not be "
                    + "checked against it.");
        }
        return Evidence.of(held.text());
    }

    private static String status(HeldPdf held, String version) {
        if (!TenderExcelSchema.hasSourceText(version)) return "v1";
        return held.scanned() ? "scanned" : "ok";
    }

    private static String note(HeldPdf held, String version) {
        Evidence ev = evidence(held, version);
        return ev.available() ? null : ev.reason();
    }

    private static synchronized void hold(HttpSession session, String importId, HeldPdf pdf) {
        if (session == null) return;
        Held held = session.getAttribute(SESSION_KEY) instanceof Held h ? h : new Held();
        held.byImport.put(importId, pdf);
        while (held.byImport.size() > MAX_HELD) held.byImport.remove(held.byImport.keySet().iterator().next());
        session.setAttribute(SESSION_KEY, held);
    }

    private static synchronized HeldPdf held(HttpSession session, String importId) {
        if (session == null || importId == null) return null;
        return session.getAttribute(SESSION_KEY) instanceof Held h ? h.byImport.get(importId) : null;
    }
}
