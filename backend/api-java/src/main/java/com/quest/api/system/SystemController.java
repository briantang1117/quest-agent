package com.quest.api.system;

import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    public record SystemInfo(String service, String version, String persistence) {}
    private final String persistence;
    public SystemController(@Value("${quest.persistence.mode:disabled}") String persistence) { this.persistence = persistence; }
    @GetMapping("/api/v1/system/info")
    public ApiResponse<SystemInfo> info(HttpServletRequest request) {
        return ApiResponse.success(new SystemInfo("api-java", "0.1.0", persistence), TraceFilter.traceId(request));
    }
}
