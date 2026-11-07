package com.quest.api.common;

import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class, MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception exception, HttpServletRequest request) {
        return error(400, 40000, "Invalid request", request);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException exception, HttpServletRequest request) {
        return error(403, 40300, "Access denied", request);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> missing(NoResourceFoundException exception, HttpServletRequest request) {
        return error(404, 40400, "Resource not found", request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            return error(status, status * 100, "Request failed", request);
        }
        LOG.error("Unhandled request failure, traceId={}", TraceFilter.traceId(request), exception);
        return error(500, 50000, "Internal server error", request);
    }
    private ResponseEntity<ApiResponse<Void>> error(int status, int code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, message, TraceFilter.traceId(request)));
    }
}
