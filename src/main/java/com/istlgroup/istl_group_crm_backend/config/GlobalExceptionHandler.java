package com.istlgroup.istl_group_crm_backend.config;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
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