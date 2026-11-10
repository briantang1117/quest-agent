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
        HandlerMethodValidationException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception exception, HttpServletRequest request) {
        return error(400, 40000, "Invalid request", request);
    }
    @ExceptionHandler(com.quest.api.auth.UserFailure.class)
    ResponseEntity<ApiResponse<Void>> userFailure(com.quest.api.auth.UserFailure exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), request);
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
        if (isClientGone(exception)) {
            // 浏览器断开/刷新导致写响应失败，不是服务端故障；响应已不可用，无法再写JSON。
            LOG.debug("Client disconnected, traceId={}", TraceFilter.traceId(request));
            return null;
        }
        if (exception instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            return error(status, status * 100, "Request failed", request);
        }
        LOG.error("Unhandled request failure, traceId={}", TraceFilter.traceId(request), exception);
        return error(500, 50000, "Internal server error", request);
    }
    /** 沿 cause 链识别客户端主动断开，避免把正常断连记为未处理异常。 */
    private static boolean isClientGone(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) return true;
            if (cause instanceof org.apache.catalina.connector.ClientAbortException) return true;
            String name = cause.getClass().getName();
            if (name.equals("org.apache.catalina.connector.ClientAbortException")) return true;
            if (cause.getMessage() != null && cause.getMessage().contains("Broken pipe")) return true;
            if (cause instanceof java.io.IOException && cause.getMessage() != null
                && cause.getMessage().contains("Connection reset")) return true;
        }
        return false;
    }
    private ResponseEntity<ApiResponse<Void>> error(int status, int code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, message, TraceFilter.traceId(request)));
    }
}
