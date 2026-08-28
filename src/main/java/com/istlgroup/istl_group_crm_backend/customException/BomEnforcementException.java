package com.istlgroup.istl_group_crm_backend.customException;

import java.util.List;
import java.util.stream.Collectors;

import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.Violation;

/**
 * BomEnforcementException — thrown by {@code BomProcurementGuard.enforce} when a
 * purchase order would breach the project BOM, either by containing a line that
 * matches no live BOM line or by taking the project's total ordered quantity above
 * the BOM quantity.
 *
 * <p>Converted to HTTP 409 BOM_LIMIT_EXCEEDED with the full violation list, which
 * the frontend renders as a blocking dialog offering a route to amend the BOM.
 * 409 rather than 400 because the request is well-formed — it conflicts with the
 * current state of the BOM budget — and because the PO screens already return 400
 * for ordinary field validation, which the frontend could not tell apart.
 *
 * <p>Modelled on {@link SessionLimitException}: a RuntimeException carrying a
 * structured payload through to a dedicated GlobalExceptionHandler branch.
 *
 * <p><b>Note:</b> all three PO write paths and their controllers catch
 * {@code Exception} and flatten it to a string, so this type must be re-thrown
 * ahead of those generic catches or the structured payload is lost.
 */
public class BomEnforcementException extends RuntimeException {

    private final String projectUniqueId;
    private final List<Violation> violations;

    public BomEnforcementException(String projectUniqueId, List<Violation> violations) {
        super(summarise(violations));
        this.projectUniqueId = projectUniqueId;
        this.violations = List.copyOf(violations);
    }

    public String getProjectUniqueId() { return projectUniqueId; }

    public List<Violation> getViolations() { return violations; }

    /**
     * A human one-liner for the legacy consumers that only read {@code .message}.
     * The per-line detail lives in {@link #getViolations()}.
     */
    private static String summarise(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) return "Purchase order breaches the project BOM.";
        if (violations.size() == 1) return violations.get(0).message();
        return violations.size() + " line(s) breach the project BOM: "
             + violations.stream().map(Violation::itemName).collect(Collectors.joining(", "));
    }
}
