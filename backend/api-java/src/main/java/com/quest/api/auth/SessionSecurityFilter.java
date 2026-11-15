package com.quest.api.auth;

import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** JWT 只证明签名；业务身份、状态及权限每次都由数据库会话确认。 */
final class SessionSecurityFilter extends OncePerRequestFilter {
    private final UserService users;
    private final com.quest.api.admin.AdminControlService controls;
    private final ObjectMapper mapper;
    SessionSecurityFilter(UserService users, ObjectMapper mapper,com.quest.api.admin.AdminControlService controls) { this.users = users; this.mapper = mapper; this.controls=controls; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                var identity = users.session(jwt.getToken().getSubject(), jwt.getToken().getClaimAsString("sid"));
                request.setAttribute(UserController.IDENTITY, identity);
                var verified = new JwtAuthenticationToken(jwt.getToken(), List.of(new SimpleGrantedAuthority("ROLE_" + identity.user().role().toUpperCase(java.util.Locale.ROOT))));
                SecurityContextHolder.getContext().setAuthentication(verified);
                String path = request.getRequestURI().substring(request.getContextPath().length());
                boolean selfService = ("GET".equals(request.getMethod()) && Set.of("/api/v1/auth/me","/api/v1/auth/features").contains(path))
                    || ("POST".equals(request.getMethod()) && (path.equals("/api/v1/auth/change-password") || path.equals("/api/v1/auth/logout")));
                if (identity.user().mustChangePassword() && !selfService) throw new UserFailure(403, 40310, "Password change required");
                boolean adminRoute = path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/");
                if (adminRoute && !identity.user().role().equals("admin")) throw UserFailure.denied();
                if(identity.user().role().equals("student")){
                    if(path.startsWith("/api/v1/coach/"))controls.requireFeature(Long.parseLong(identity.user().id()),"coach");
                    if(path.startsWith("/api/v1/study/syllab"))controls.requireFeature(Long.parseLong(identity.user().id()),"syllabus");
                }
                // AI 调用审计只读查询：Admin 可查，普通用户与内容管理端点不得复用此路径。
                boolean aiAudit = (path.equals("/api/v1/admin/ai-calls") || path.matches("/api/v1/admin/ai-calls/[0-9]+")) && request.getMethod().equals("GET");
                // Admin 白名单只开放账号元数据、本人认证与系统信息，其余未来内容端点也默认拒绝。
                boolean userManagement = (path.matches("/api/v1/admin/users/[^/]+/usage/[^/]+/reconcile") && request.getMethod().equals("POST"))
                    || (path.matches("/api/v1/admin/users/[^/]+/(controls|credits|force-logout)") && Set.of("GET","POST").contains(request.getMethod()))
                    || (path.matches("/api/v1/admin/users/[^/]+/features/[^/]+") && request.getMethod().equals("PATCH"))
                    || (Set.of("/api/v1/admin/events","/api/v1/admin/statistics").contains(path) && request.getMethod().equals("GET"))
                    || (path.equals("/api/v1/admin/users") && (request.getMethod().equals("GET") || request.getMethod().equals("POST")))
                    || (path.matches("/api/v1/admin/users/[^/]+/status") && request.getMethod().equals("PATCH"))
                    || (path.matches("/api/v1/admin/users/[^/]+/reset-password") && request.getMethod().equals("POST"));
                // 公共内容使用独立白名单，不扩大到历史题表/私人内容/练习端点。
                boolean publicManagement = (path.equals("/api/v1/admin/public-banks") && (request.getMethod().equals("GET") || request.getMethod().equals("POST")))
                    || (path.matches("/api/v1/admin/public-banks/[^/]+") && request.getMethod().equals("GET"))
                    || (path.matches("/api/v1/admin/public-banks/[^/]+/imports") && (request.getMethod().equals("GET") || request.getMethod().equals("POST")))
                    || (path.matches("/api/v1/admin/public-questions/[^/]+") && request.getMethod().equals("DELETE"))
                    || (path.matches("/api/v1/admin/public-questions/[^/]+/withdraw") && request.getMethod().equals("POST"))
                    || (path.matches("/api/v1/admin/public-banks/[^/]+/publish") && request.getMethod().equals("POST"))
                    || (path.matches("/api/v1/admin/public-banks/[^/]+/pdf-imports") && request.getMethod().equals("POST"))
                    || (path.matches("/api/v1/admin/public-banks/[^/]+/(documents|questions|question-summary)") && request.getMethod().equals("GET"))
                    || (path.matches("/api/v1/admin/public-documents/[^/]+/start") && request.getMethod().equals("POST"))
                    || (path.matches("/api/v1/admin/public-documents/[^/]+/file") && request.getMethod().equals("GET"))
                    || (path.matches("/api/v1/admin/public-imports/[^/]+") && request.getMethod().equals("GET"))
                    || (path.matches("/api/v1/admin/public-questions/[^/]+") && (request.getMethod().equals("GET") || request.getMethod().equals("PUT")))
                    || (path.matches("/api/v1/admin/public-questions/[^/]+/(confirm|publish)") && request.getMethod().equals("POST"));
                boolean syllabusManagement = (path.equals("/api/v1/admin/syllabi") && Set.of("GET","POST").contains(request.getMethod()))
                    || (path.matches("/api/v1/admin/syllabi/[0-9a-fA-F-]{36}") && Set.of("GET","PUT").contains(request.getMethod()))
                    || (path.matches("/api/v1/admin/syllabi/[0-9a-fA-F-]{36}/(publish|archive|retry-review|rebuild-index)") && request.getMethod().equals("POST"))
                    || (path.matches("/api/v1/admin/syllabi/[0-9a-fA-F-]{36}/file") && request.getMethod().equals("GET"));
                if (identity.user().role().equals("admin") && !selfService && !userManagement && !publicManagement && !aiAudit && !syllabusManagement && !path.equals("/api/v1/system/info")
                        && !path.equals("/actuator/health") && !path.startsWith("/actuator/health/")) throw UserFailure.denied();
            } catch (UserFailure failure) {
                SecurityContextHolder.clearContext();
                response.setStatus(failure.status()); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                if (failure.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
                mapper.writeValue(response.getOutputStream(), ApiResponse.error(failure.code(), failure.getMessage(), TraceFilter.traceId(request)));
                return;
            } catch (RuntimeException failure) {
                // 类型错误的 claim 及非法 session/subject 均拒绝，不暴露解析细节。
                SecurityContextHolder.clearContext();
                response.setStatus(401); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getOutputStream(), ApiResponse.error(40100, "Invalid credentials or session", TraceFilter.traceId(request)));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
