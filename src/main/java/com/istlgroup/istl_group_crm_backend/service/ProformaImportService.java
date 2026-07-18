package com.istlgroup.istl_group_crm_backend.service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.util.GroqClient;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * ProformaImportService — reads a vendor proforma invoice / quotation /
 * tax invoice (PDF or image) and extracts the fields of the "Generate
 * Purchase Order" form as structured JSON, so the user can import instead
 * of typing everything manually.
 *
 * PDF  → text extracted with iText, sent to the Groq text model.
 * Image → sent to the Groq vision model as base64.
 *
 * The AI's answer is validated as JSON and lightly sanity-checked
 * (qty × rate vs printed amount) before being returned to the frontend.
 */
@Service
@Slf4j
public class ProformaImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10 MB

    @Autowired
    private GroqClient groqClient;

    /* ─────────────────────────────────────────────────────────────────── */

    public JsonNode parse(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File too large (max 10 MB)");
        }

        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();

        String raw;
        if (ct.contains("pdf") || name.endsWith(".pdf")) {
            String text = extractPdfText(file.getBytes());
            if (text.strip().length() < 40) {
                // Scanned PDF with no text layer — we can't rasterize here,
                // so ask the user for a photo/screenshot instead.
                throw new IllegalArgumentException(
                    "This PDF appears to be scanned (no selectable text). " +
                    "Please upload a photo or screenshot of the invoice (JPG/PNG) instead.");
            }
            raw = groqClient.complete(SYSTEM_PROMPT, text, 2000, 0.0);
        } else if (ct.startsWith("image/") || name.matches(".*\\.(png|jpe?g|webp)$")) {
            String b64 = Base64.getEncoder().encodeToString(file.getBytes());
            String mime = ct.startsWith("image/") ? ct
                    : (name.endsWith(".png") ? "image/png" : "image/jpeg");
            raw = groqClient.completeWithVision(SYSTEM_PROMPT,
                    "Extract this proforma invoice into the JSON format described.",
                    b64, mime, 2000, 0.0);
        } else {
            throw new IllegalArgumentException("Unsupported file type. Upload a PDF, JPG or PNG.");
        }

        JsonNode parsed = toJson(raw);
        return sanityCheck(parsed);
    }

    /* ── PDF text extraction (all pages, layout-aware) ─────────────────── */

    private String extractPdfText(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            int pages = Math.min(pdf.getNumberOfPages(), 5); // POs/PIs are short
            for (int i = 1; i <= pages; i++) {
                sb.append(PdfTextExtractor.getTextFromPage(
                        pdf.getPage(i), new LocationTextExtractionStrategy()));
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    /* ── JSON handling ──────────────────────────────────────────────────── */

    private JsonNode toJson(String raw) {
        if (raw == null) throw new IllegalStateException("AI returned no response");
        String s = raw.strip();
        // Strip markdown fences if the model added them despite instructions
        if (s.startsWith("```")) {
            s = s.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Could not read the document. Please try again or fill manually.");
        }
        try {
            return MAPPER.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("Proforma AI response was not valid JSON: {}", s);
            throw new IllegalStateException("Could not read the document. Please try again or fill manually.");
        }
    }

    /**
     * Cross-check each line: if qty × rate disagrees badly with the printed
     * line amount (some vendor documents contain typos, e.g. "37,00.00"),
     * trust the arithmetic and add a warning the UI can show.
     */
    private JsonNode sanityCheck(JsonNode root) {
        List<String> warnings = new ArrayList<>();
        JsonNode items = root.path("items");
        if (items.isArray()) {
            for (JsonNode it : items) {
                double qty  = it.path("qty").asDouble(0);
                double rate = it.path("pricePerUnit").asDouble(0);
                double amt  = it.path("amount").asDouble(-1);
                if (qty > 0 && rate > 0 && amt > 0) {
                    double calc = qty * rate;
                    if (Math.abs(calc - amt) / Math.max(calc, amt) > 0.02) {
                        warnings.add("Printed amount for \"" + it.path("description").asText("")
                                + "\" (" + amt + ") does not match qty × rate (" + calc
                                + "). Using qty × rate — please verify.");
                    }
                }
            }
        }
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) root;
        var warnArr = result.putArray("warnings");
        // keep any warnings the model itself produced
        root.path("modelWarnings").forEach(w -> warnArr.add(w.asText()));
        warnings.forEach(warnArr::add);
        return result;
    }

    /* ── Extraction prompt ──────────────────────────────────────────────── */

    private static final String SYSTEM_PROMPT = """
        You extract data from Indian vendor documents (proforma invoices, tax invoices, quotations)
        so a Purchase Order form can be pre-filled. The BUYER is Sesola Power Projects Private Limited —
        never output Sesola as the vendor; the VENDOR/SUPPLIER is the other party issuing the document.

        Respond with ONLY a JSON object, no markdown, no commentary, exactly this shape:
        {
          "documentType": "PROFORMA_INVOICE | TAX_INVOICE | QUOTATION | OTHER",
          "referenceNo": "invoice/quotation number, e.g. INV-143",
          "referenceDate": "DD/MM/YYYY or empty",
          "vendorName": "supplier company name",
          "vendorAddress": "supplier full address, single string, lines joined with \\n",
          "supplierGst": "supplier GSTIN (15 chars) or empty",
          "vendorPhone": "supplier phone or empty",
          "vendorEmail": "supplier email or empty",
          "paymentTerms": "e.g. 100% Advance, or empty",
          "deliveryTerms": "delivery schedule/terms or empty",
          "bankAccountName": "from bank details section, or empty",
          "bankName": "", "bankBranch": "", "bankAccountNo": "", "bankIfsc": "",
          "items": [
            {
              "description": "item name and description combined",
              "hsn": "HSN/SAC code or empty",
              "unit": "Nos/Mtr/Kgs/... default Nos",
              "qty": number,
              "pricePerUnit": number,
              "gstPercent": number (total GST %, CGST+SGST combined, e.g. 9+9 = 18; default 18),
              "amount": number (printed line amount incl. tax if that is what is printed, else excl.)
            }
          ],
          "subTotal": number or null,
          "grandTotal": number or null,
          "modelWarnings": ["any field you were unsure about"]
        }

        Rules:
        - Numbers must be plain JSON numbers without currency symbols, commas or units.
        - Do not invent values; use "" or null when a field is absent. Never guess an HSN code.
        - GST: if shown as CGST 9% + SGST 9%, gstPercent is 18. If IGST 18%, gstPercent is 18.
        - vendor fields are the SUPPLIER's (the letterhead / "For <company>" side), not the Bill To side.
        - Multi-line item descriptions must be joined into one string.
        """;
}