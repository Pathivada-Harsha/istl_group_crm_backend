package com.istlgroup.istl_group_crm_backend.util;

import java.util.Set;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * BomRateVisibility — the ONE rule for "may this role see BOM pricing?".
 *
 * Field-staff access levels see quantities and specifications but NOT pricing
 * (unit_rate / amount / totals). Lifted out of ProjectDetailController so the
 * project BOM tab and the procurement BOM picker can never drift apart: the
 * spec requires that a role which cannot see rates on the BOM tab must not see
 * them in the picker either.
 *
 * Conservative by design — gate only clearly field-facing roles. Tune this set
 * once the project_access level model is confirmed.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public final class BomRateVisibility {

    private BomRateVisibility() {}

    /** Roles that see quantities and specs but never rates. */
    private static final Set<String> NO_RATES = Set.of(
            "SITE_ENGINEER", "SITE_TECHNICIAN", "TECHNICIAN", "FIELD_ENGINEER");

    /**
     * @param userRole the caller's real role, resolved from the authenticated session
     *                 in any casing/spacing.
     *
     *                 <p>Null is still treated as unrestricted, but it is now
     *                 unreachable from a request: {@code @ActingUserRole} never yields
     *                 null and 401s instead. It used to be very reachable — a
     *                 SITE_ENGINEER saw every rate simply by omitting the
     *                 {@code User-Role} header. The branch is kept only for the
     *                 internal callers that pass a role along from elsewhere.
     */
    public static boolean canSeeRates(String userRole) {
        if (userRole == null) return true;
        String r = RoleNormalizer.normalize(userRole);
        return r == null || !NO_RATES.contains(r);
    }
}
