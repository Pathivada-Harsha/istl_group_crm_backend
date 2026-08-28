package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.List;
import java.util.Map;

/**
 * What a parse hands back to the review modal.
 *
 * <p>Note what is <em>not</em> here: a count of fields filled in. The import no
 * longer writes anything; it proposes a list of values, each with the page and
 * the line it came from, and the user ticks the ones to keep.
 *
 * @param complete   did the core identity plus one commercial/date field survive
 *                   validation — see the gate in {@code TenderService}
 * @param message    one line for the modal header, in plain words
 * @param origin     {@code regex} or {@code regex+ai}
 * @param fields     every surviving value, with provenance
 * @param discarded  values that were read and then thrown away, and why
 * @param pageCount  pages in the source document
 * @param summaryFromPage where the summary block starts, or null if none was found
 * @param summaryToPage   where it ends
 */
public record TenderParseResult(boolean complete,
                                String message,
                                String origin,
                                List<ExtractedField> fields,
                                List<Map<String, Object>> boqItems,
                                List<Map<String, Object>> eligibilityCriteria,
                                List<Discarded> discarded,
                                int pageCount,
                                Integer summaryFromPage,
                                Integer summaryToPage) {

    /** A value the extractor produced and validation rejected. */
    public record Discarded(String field, String value, String reason) {}
}
