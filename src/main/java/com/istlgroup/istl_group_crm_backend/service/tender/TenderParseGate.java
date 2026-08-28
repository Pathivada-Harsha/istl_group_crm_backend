package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Stage 5 — is this parse complete?
 *
 * <p>The check it replaces counted how many fields the regex extractor
 * <em>produced</em>. That counted garbage: a sentence fragment in
 * {@code issuingAuthority} and a date in {@code tenderNumber} both counted, so
 * every parse looked good enough and the escalation path behind the gate was
 * unreachable in practice.
 *
 * <p>What counts now is what came through {@link TenderFieldValidator} alive:
 * the tender's identity, plus at least one commercial or date detail. Presence
 * alone no longer qualifies.
 */
public final class TenderParseGate {

    /** The tender's identity. All three must survive. */
    public static final List<String> CORE_FIELDS =
            List.of("tenderNumber", "tenderName", "issuingAuthority");

    /** At least one of these must survive too. */
    public static final Set<String> DETAIL_FIELDS =
            Set.of("estimatedValue", "emdAmount", "submissionDeadline");

    private TenderParseGate() {}

    public static boolean isComplete(Collection<String> survivingFields) {
        if (survivingFields == null || survivingFields.isEmpty()) return false;
        return survivingFields.containsAll(CORE_FIELDS)
                && DETAIL_FIELDS.stream().anyMatch(survivingFields::contains);
    }

    /** Which identity fields are missing, for the message the modal shows. */
    public static List<String> missingCore(Collection<String> survivingFields) {
        return CORE_FIELDS.stream()
                .filter(f -> survivingFields == null || !survivingFields.contains(f))
                .toList();
    }
}
