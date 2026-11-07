package com.quest.common.api;

/** All business HTTP responses use this envelope; Actuator retains its native health protocol. */
public record ApiResponse<T>(int code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data, String traceId) { return new ApiResponse<>(0, "OK", data, traceId); }
    public static ApiResponse<Void> error(int code, String message, String traceId) { return new ApiResponse<>(code, message, null, traceId); }
}
