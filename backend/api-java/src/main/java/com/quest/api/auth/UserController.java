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
    private final com.quest.api.admin.AdminControlService audit;
    @org.springframework.beans.factory.annotation.Value("${quest.security.refresh.secure:false}") private boolean secureCookie;
    private static final String COOKIE="quest_refresh";
    public UserController(UserService users, LoginLimiter limiter, LoginSource source,com.quest.api.admin.AdminControlService audit) { this.users = users; this.limiter = limiter; this.source = source; this.audit=audit; }
    // 请求 DTO 不生成含明文凭据的 toString。
    public record Login(String username, String password, Boolean remember) { @Override public String toString() { return "Login[redacted]"; } }
    public record Change(String currentPassword, String newPassword) { @Override public String toString() { return "Change[redacted]"; } }
    public record Create(String username, String displayName, String initialPassword) { @Override public String toString() { return "Create[redacted]"; } }
    public record Reset(String newPassword) { @Override public String toString() { return "Reset[redacted]"; } }
    public record Status(String status) {}
    @PostMapping("/auth/login")
    public ApiResponse<TokenResult> login(@RequestBody Login body, HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        try{
        // 先限流，所有尝试（含成功）计数，避免命中账号/来源后绕过。
        String name = body.username();
        limiter.acquire(name == null || name.length() > 32 ? "<invalid>" : name.toLowerCase(java.util.Locale.ROOT), source.source(request));
        if (name == null || name.length() > 32 || body.password() == null || body.password().length() > 128)
            throw UserFailure.invalid();
        var issued=users.loginSession(name,body.password(),TraceFilter.traceId(request),Boolean.TRUE.equals(body.remember()));
        cookie(response,issued);
        loginLog(request,Long.valueOf(issued.token().user().id()),name,"ok");
        return ok(issued.token(), request);
        }catch(RuntimeException failure){loginLog(request,null,body.username(),"failed");throw failure;}
    }
    @GetMapping("/auth/me")
    public ApiResponse<Profile> me(HttpServletRequest request) { return ok(identity(request).user(), request); }
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@CookieValue(name=COOKIE,required=false)String value,@RequestHeader(name="X-Refresh-Intent",defaultValue="")String intent,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        if(request.getAttribute(IDENTITY) instanceof SessionIdentity who)users.logout(who);
        else {if(!intent.equals("1"))throw UserFailure.denied();var user=users.logoutRefresh(value);if(user!=null)audit.log(Long.valueOf(user.id()),user.username(),"auth.logout","ok",null,source.source(request),request.getHeader("User-Agent"),"",TraceFilter.traceId(request));}
        clearCookie(response);return ok(null,request);
    }
    @PostMapping("/auth/change-password")
    public ApiResponse<TokenResult> change(@RequestBody Change body, HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        var issued=users.changePasswordSession(identity(request),body.currentPassword(),body.newPassword(),TraceFilter.traceId(request));cookie(response,issued);return ok(issued.token(),request);
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
    @PostMapping("/auth/refresh")
    public ApiResponse<TokenResult> refresh(@CookieValue(name=COOKIE,required=false)String value,@RequestHeader(name="X-Refresh-Intent",defaultValue="")String intent,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response){
        if(!intent.equals("1"))throw UserFailure.denied();
        try{var issued=users.refresh(value);cookie(response,issued);return ok(issued.token(),request);}
        catch(UserFailure failure){if(failure.status()==401)clearCookie(response);throw failure;}
    }
    private void cookie(jakarta.servlet.http.HttpServletResponse response,UserService.Issued issued){
        var builder=org.springframework.http.ResponseCookie.from(COOKIE,issued.refresh()).httpOnly(true).secure(secureCookie).sameSite("Strict").path("/");
        if(issued.remember())builder.maxAge(java.time.Duration.between(java.time.Instant.now(),issued.refreshExpires()));
        response.addHeader("Set-Cookie",builder.build().toString());
    }
    private void clearCookie(jakarta.servlet.http.HttpServletResponse response){response.addHeader("Set-Cookie",org.springframework.http.ResponseCookie.from(COOKIE,"").httpOnly(true).secure(secureCookie).sameSite("Strict").path("/").maxAge(0).build().toString());}
    private void loginLog(HttpServletRequest request,Long actor,String username,String outcome){try{audit.loginLog(actor,username,outcome,source.source(request),request.getHeader("User-Agent"),TraceFilter.traceId(request));}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("登录审计记录失败：type={}",failure.getClass().getSimpleName());}}
    private static SessionIdentity identity(HttpServletRequest request) {
        var identity = request.getAttribute(IDENTITY);
        if (!(identity instanceof SessionIdentity session)) throw UserFailure.invalid();
        return session;
    }
    private static <T> ApiResponse<T> ok(T body, HttpServletRequest request) { return ApiResponse.success(body, TraceFilter.traceId(request)); }
}
