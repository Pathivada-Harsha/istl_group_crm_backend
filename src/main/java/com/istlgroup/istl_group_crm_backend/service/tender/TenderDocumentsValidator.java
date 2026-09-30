package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The AI's documents checklist, checked against the located section.
 *
 * <p>Same rule as eligibility: every document must be anchored to the passage
 * that asks for it, or it is dropped. A kept document carries that passage and
 * its page in {@code notes}, so whoever collects it can see exactly what the
 * tender demands (attested? CA-certified? in which format?).
 */
public final class TenderDocumentsValidator {

    static final int MAX_DOCUMENTS = 40;
    /** tender_documents.document_name is VARCHAR(255). */
    static final int MAX_NAME = 250;
    static final int MAX_NOTE = 600;

    public record Result(List<Map<String, Object>> documents, List<TenderParseResult.Discarded> discarded) {}

    private TenderDocumentsValidator() {}

    public static Result validate(List<Map<String, Object>> proposed, TenderText section) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<TenderParseResult.Discarded> discarded = new ArrayList<>();
        if (proposed == null || section == null || section.isEmpty()) return new Result(kept, discarded);

        TenderClauseFinder finder = new TenderClauseFinder(section);
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : proposed) {
            String name = TenderValues.tidy(str(row.get("documentName")));
            if (name == null) continue;
            TenderClauseFinder.Clause clause = finder.find(str(row.get("sourceStart")), str(row.get("sourceEnd")));
            if (clause == null) {
                discarded.add(new TenderParseResult.Discarded("documents", name,
                        "no passage in the document asks for it"));
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("documentName", TenderValues.clip(name, MAX_NAME));
            out.put("notes", "p." + clause.page() + " — " + TenderValues.clip(clause.text(), MAX_NOTE));
            out.put("sourcePage", clause.page());
            kept.add(out);
            if (kept.size() >= MAX_DOCUMENTS) break;
        }
        return new Result(kept, discarded);
    }

    private static String str(Object o) {
        return TenderValues.aiText(o);
    }
}
