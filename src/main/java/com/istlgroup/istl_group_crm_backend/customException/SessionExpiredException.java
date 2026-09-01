package com.istlgroup.istl_group_crm_backend.customException;

/**
 * The acting user could not be established from the authenticated session.
 *
 * <p>Maps to 401 with the SAME body {@code filter/SessionFilter} already writes, so the
 * frontend's {@code utils/setupFetchInterceptor.js} reacts identically (clear storage,
 * redirect to /login) whether the filter or an argument resolver rejected the request.
 *
 * <p>There is deliberately no other outcome. Before this existed, several controllers
 * fell back to user {@code 1L} / role {@code "USER"} when identity was missing, which
 * served the request as a guessed user. Missing identity is now always 401.
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException(String message) {
        super(message);
    }
}
