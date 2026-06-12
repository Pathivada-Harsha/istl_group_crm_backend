package com.istlgroup.istl_group_crm_backend.customException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// ✅ Changed from NOT_FOUND (404) to BAD_REQUEST (400)
// GlobalExceptionHandler overrides this anyway and returns 401 for login errors
// But this ensures raw Spring error pages show the right status
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CustomException extends Exception {

    public CustomException(String message) {
        super(message);
    }
}