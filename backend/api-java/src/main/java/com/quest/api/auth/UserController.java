package com.quest.api.auth;

import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.quest.api.auth.UserModels.*;

@RestController
@RequestMapping("/api/v1")
public class UserController {
    public static final String IDENTITY = UserController.class.getName() + ".identity";
    private final UserService users;
    private final LoginLimiter limiter;
    private final LoginSource source;
    public UserController(UserService users, LoginLimiter limiter, LoginSource source) { this.users = users; this.limiter = limiter; this.source = source; }
    // 请求 DTO 不生成含明文凭据的 toString。
    public record Login(String username, String password) { @Override public String toString() { return "Login[redacted]"; } }
    public record Change(String currentPassword, String newPassword) { @Override public String toString() { return "Change[redacted]"; } }
    public record Create(String username, String displayName, String initialPassword) { @Override public String toString() { return "Create[redacted]"; } }
    public record Reset(String newPassword) { @Override public String toString() { return "Reset[redacted]"; } }
    public record Status(String status) {}
    @PostMapping("/auth/login")
    public ApiResponse<TokenResult> login(@RequestBody Login body, HttpServletRequest request) {
        // 先限流，所有尝试（含成功）计数，避免命中账号/来源后绕过。
        String name = body.username();
        limiter.acquire(name == null || name.length() > 32 ? "<invalid>" : name.toLowerCase(java.util.Locale.ROOT), source.source(request));
        if (name == null || name.length() > 32 || body.password() == null || body.password().length() > 128)
            throw UserFailure.invalid();
        return ok(users.login(name, body.password(), TraceFilter.traceId(request)), request);
    }
    @GetMapping("/auth/me")
    public ApiResponse<Profile> me(HttpServletRequest request) { return ok(identity(request).user(), request); }
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) { users.logout(identity(request)); return ok(null, request); }
    @PostMapping("/auth/change-password")
    public ApiResponse<TokenResult> change(@RequestBody Change body, HttpServletRequest request) {
        return ok(users.changePassword(identity(request), body.currentPassword(), body.newPassword(), TraceFilter.traceId(request)), request);
    }
    @GetMapping("/admin/users")
    public ApiResponse<UserPage> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "") String status, HttpServletRequest request) {
        return ok(users.list(identity(request), page, size, keyword, status), request);
    }
    @PostMapping("/admin/users")
    public ApiResponse<Profile> create(@RequestBody Create body, HttpServletRequest request) {
        return ok(users.create(identity(request), body.username(), body.displayName(), body.initialPassword(), TraceFilter.traceId(request)), request);
    }
    @PatchMapping("/admin/users/{id}/status")
    public ApiResponse<Profile> status(@PathVariable String id, @RequestBody Status body, HttpServletRequest request) {
        return ok(users.setStatus(identity(request), id, body.status(), TraceFilter.traceId(request)), request);
    }
    @PostMapping("/admin/users/{id}/reset-password")
    public ApiResponse<Profile> reset(@PathVariable String id, @RequestBody Reset body, HttpServletRequest request) {
        return ok(users.resetPassword(identity(request), id, body.newPassword(), TraceFilter.traceId(request)), request);
    }
    private static SessionIdentity identity(HttpServletRequest request) {
        var identity = request.getAttribute(IDENTITY);
        if (!(identity instanceof SessionIdentity session)) throw UserFailure.invalid();
        return session;
    }
    private static <T> ApiResponse<T> ok(T body, HttpServletRequest request) { return ApiResponse.success(body, TraceFilter.traceId(request)); }
}
