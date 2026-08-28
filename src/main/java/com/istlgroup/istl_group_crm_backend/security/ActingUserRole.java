package com.istlgroup.istl_group_crm_backend.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role of the user making this request, read from the database for the id on the
 * authenticated session.
 *
 * <p>Replaces {@code @RequestHeader("User-Role")} / {@code "X-User-Role"} everywhere.
 * Those headers are set by the browser: a logged-in non-admin could send
 * {@code User-Role: SUPERADMIN} and be allowed to rewrite the role hierarchy. Nothing on
 * the request can influence what this resolves to — see {@link ActingUserService}.
 *
 * <p>Always non-null and always the caller's real, current role. Handlers that used to
 * treat a missing role header as "unrestricted" will now see the true role and enforce
 * accordingly; that is the point of the change, not a regression.
 *
 * <p>Supported parameter type: {@code String}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ActingUserRole {
}
