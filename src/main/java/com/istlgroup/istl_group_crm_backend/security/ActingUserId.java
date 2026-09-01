package com.istlgroup.istl_group_crm_backend.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The id of the user making this request, taken from the authenticated session.
 *
 * <p>Replaces {@code @RequestHeader("User-Id")} everywhere. That header is set by the
 * browser and is therefore attacker-controlled: sending somebody else's id used to make
 * the server treat you as them. Nothing on the request can influence what this resolves
 * to — see {@link ActingUserService}.
 *
 * <p>Supported parameter types: {@code Long}, {@code long}, {@code String}.
 *
 * <p>This is the ACTOR, never an operand. A user id that names the <em>target</em> of an
 * operation — who a lead is being assigned to, which user an admin screen is editing,
 * which user a list is filtered by — stays a {@code @PathVariable} / {@code @RequestParam}
 * / body field and is unaffected by this annotation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ActingUserId {
}
