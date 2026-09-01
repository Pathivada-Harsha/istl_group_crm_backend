package com.istlgroup.istl_group_crm_backend.config;

import com.istlgroup.istl_group_crm_backend.customException.BomEnforcementException;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.customException.NotPermittedException;
import com.istlgroup.istl_group_crm_backend.customException.SessionExpiredException;
import com.istlgroup.istl_group_crm_backend.customException.SessionLimitException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // ✅ File too large — returns clear 413 with message instead of generic 500
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(
                    "error", "FILE_TOO_LARGE",
                    "message", "File size exceeds the maximum allowed limit of 50 MB. Please upload a smaller file."
                ));
    }

    // ✅ CustomException — business logic errors (duplicate role, validation failures, etc.)
    // MUST NOT return 401 — that status makes browsers/clients treat it as an auth failure
    // and can cause session drops on prod (SameSite=None cookies).
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<Map<String, String>> handleCustomException(CustomException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", ex.getMessage()
                ));
    }

    // ✅ SessionLimitException — LOGIN ACTIVITY MODULE (Feature 2).
    // 409 with the active session list; the frontend shows the
    // "Maximum Active Sessions Reached" dialog and may retry with forceLogin.
    // MUST be declared before the RuntimeException handler.
    @ExceptionHandler(SessionLimitException.class)
    public ResponseEntity<Map<String, Object>> handleSessionLimit(SessionLimitException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                    "error", "SESSION_LIMIT_REACHED",
                    "message", ex.getMessage(),
                    "sessions", ex.getActiveSessions()
                ));
    }

    // ✅ SessionExpiredException — the acting user could not be established from the
    // session (no session, no USER_ID on it, or it points at a user row that is gone).
    // Body is byte-identical to what filter/SessionFilter writes, so the frontend's
    // fetch interceptor takes the same "clear storage and go to /login" path either way.
    // This is the ONLY outcome when identity is missing — several controllers used to
    // fall back to user 1 / role "USER" and serve the request as a guessed user.
    // MUST be declared before the RuntimeException handler.
    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<Map<String, String>> handleSessionExpired(SessionExpiredException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                    "error", "SESSION_EXPIRED",
                    "message", ex.getMessage()
                ));
    }

    // ✅ NotPermittedException — the caller is logged in but may not act on this target
    // (e.g. assigning a lead to somebody outside their reporting subtree). 403, not 401:
    // 401 makes the fetch interceptor tear the session down, and this is not an auth
    // failure. MUST be declared before the RuntimeException handler.
    @ExceptionHandler(NotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleNotPermitted(NotPermittedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "NOT_PERMITTED");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // A purchase order that would breach the project BOM — either an off-BOM line or
    // a quantity taking the project total above the BOM quantity. 409 + the full
    // violation list, which the frontend renders as a blocking dialog offering a
    // route to amend the BOM. MUST be declared before the RuntimeException handler.
    //
    // NOTE: the PO service and controller both catch Exception themselves, so in
    // practice their own explicit branches fire first. This handler is the safety net
    // for any future call site that does not add one.
    @ExceptionHandler(BomEnforcementException.class)
    public ResponseEntity<Map<String, Object>> handleBomEnforcement(BomEnforcementException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "BOM_LIMIT_EXCEEDED");
        body.put("message", ex.getMessage());
        body.put("projectId", ex.getProjectUniqueId());
        body.put("violations", ex.getViolations());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // ✅ ResponseStatusException — preserves the intended HTTP status & message
    //    (used by the expense approval workflow for 400 / 403 responses).
    //    MUST be declared before the RuntimeException handler so it takes precedence.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Request could not be processed.";
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(Map.of(
                    "error", "REQUEST_REJECTED",
                    "message", msg
                ));
    }

    // ✅ EntityNotFoundException — returns 404 with a clear message
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEntityNotFound(EntityNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                    "error", "NOT_FOUND",
                    "message", ex.getMessage() != null ? ex.getMessage() : "Resource not found."
                ));
    }

    // ✅ Missing required request parameters
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "Missing required parameter: " + ex.getParameterName()
                ));
    }

    // ✅ Wrong parameter type
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "Invalid value for parameter: " + ex.getName()
                ));
    }

    // ✅ NullPointerException
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, String>> handleNullPointer(NullPointerException ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "A required value was null. Please try again or contact support."
                ));
    }

    // ✅ RuntimeException — DB errors, not found etc.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", ex.getMessage() != null
                        ? ex.getMessage()
                        : "An unexpected error occurred. Please try again."
                ));
    }

    // ✅ Catch-all fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Something went wrong. Please try again."
                ));
    }
}