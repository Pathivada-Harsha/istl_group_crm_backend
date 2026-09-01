package com.istlgroup.istl_group_crm_backend.customException;

/**
 * The caller is authenticated but is not allowed to perform this particular
 * action on this particular target — e.g. assigning a lead to somebody outside
 * their reporting subtree.
 *
 * <p>Deliberately NOT a {@link CustomException}: that one is a validation error
 * and maps to 400. This maps to 403 so a rejected privilege escalation is
 * distinguishable from a bad payload in the logs.
 */
public class NotPermittedException extends RuntimeException {

    public NotPermittedException(String message) {
        super(message);
    }
}
