package com.istlgroup.istl_group_crm_backend.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The display name of the user making this request, from the authenticated session.
 *
 * <p>Replaces {@code @RequestHeader("X-User-Name")}. Used only for attribution strings
 * ("recorded by …"), but a caller could previously stamp somebody else's name on their
 * own writes, so it is resolved server-side like the id and the role.
 *
 * <p>Supported parameter type: {@code String}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ActingUserName {
}
