package com.istlgroup.istl_group_crm_backend.service.tender;

/**
 * One extracted value plus everything needed to review it without reopening the
 * PDF.
 *
 * <p>Provenance is not decoration. A 105-page NIT is not a document anyone will
 * re-read to check one field, so a value with no page number and no surrounding
 * text is a value nobody can confirm — and accuracy that cannot be checked
 * cannot be measured.
 *
 * @param field      the tender field name (matches the frontend's contract)
 * @param label      the label as printed in the document, or how it was found
 * @param value      the normalised value (plain rupees, yyyy-MM-dd, …)
 * @param page       1-based page it came from
 * @param sourceText the line it was read out of
 * @param origin     {@code regex} or {@code ai}
 * @param confident  true when this row should be ticked by default for import
 */
public record ExtractedField(String field, String label, String value,
                             int page, String sourceText, String origin, boolean confident) {

    public static final String REGEX = "regex";
    public static final String AI = "ai";

    public ExtractedField withValue(String newValue) {
        return new ExtractedField(field, label, newValue, page, sourceText, origin, confident);
    }

    public ExtractedField withConfidence(boolean isConfident) {
        return new ExtractedField(field, label, value, page, sourceText, origin, isConfident);
    }
}
