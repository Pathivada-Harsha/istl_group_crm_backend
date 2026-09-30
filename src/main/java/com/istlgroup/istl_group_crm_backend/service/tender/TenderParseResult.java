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
 * @param documents  the documents checklist (AI read only), each with its page and clause in notes
 * @param sectionNotes one line per child section: where it was read from, or why it is empty
 * @param eligibilitySections the pages read for qualifying requirements, empty if none
 */
public record TenderParseResult(boolean complete,
                                String message,
                                String origin,
                                List<ExtractedField> fields,
                                List<Map<String, Object>> boqItems,
                                List<Map<String, Object>> eligibilityCriteria,
                                List<Map<String, Object>> documents,
                                List<String> sectionNotes,
                                List<Discarded> discarded,
                                int pageCount,
                                Integer summaryFromPage,
                                Integer summaryToPage,
                                List<TenderSectionLocator.Section> eligibilitySections) {

    /** A value the extractor produced and validation rejected. */
    public record Discarded(String field, String value, String reason) {}
}
