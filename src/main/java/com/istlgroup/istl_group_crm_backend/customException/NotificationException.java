package com.istlgroup.istl_group_crm_backend.customException;

import org.springframework.http.HttpStatus;

/**
 * Thrown by the notification layer for ownership / not-found violations.
 * Carries an HttpStatus so the GlobalExceptionHandler can translate it
 * to the correct response code (404 not found, 403 forbidden, etc.).
 *
 * Extends RuntimeException so it can be thrown from inside streams/lambdas
 * and is already swept up by the existing RuntimeException handler if a
 * dedicated handler is not added.
 */
public class NotificationException extends RuntimeException {

    private final HttpStatus status;

    public NotificationException(String message) {
        this(message, HttpStatus.BAD_REQUEST);
    }

    public NotificationException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    // Convenience factories
    public static NotificationException notFound() {
        return new NotificationException("Notification not found.", HttpStatus.NOT_FOUND);
    }

    public static NotificationException forbidden() {
        return new NotificationException(
                "You are not allowed to access this notification.", HttpStatus.FORBIDDEN);
    }
}
