package com.istlgroup.istl_group_crm_backend.customException;

import java.util.List;

import com.istlgroup.istl_group_crm_backend.dto.ActiveSessionDto;

/**
 * SessionLimitException — thrown by SessionRegistryService when a user already
 * has the maximum number of ACTIVE sessions and did not send forceLogin=true.
 * GlobalExceptionHandler converts it to HTTP 409 SESSION_LIMIT_REACHED with the
 * list of active sessions, which the frontend uses to render the warning dialog.
 */
public class SessionLimitException extends RuntimeException {

    private final List<ActiveSessionDto> activeSessions;

    public SessionLimitException(String message, List<ActiveSessionDto> activeSessions) {
        super(message);
        this.activeSessions = activeSessions;
    }

    public List<ActiveSessionDto> getActiveSessions() {
        return activeSessions;
    }
}
