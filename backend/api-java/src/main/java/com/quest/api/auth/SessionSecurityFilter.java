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

/** JWT 只证明签名；业务身份、状态及权限每次都由数据库会话确认。 */
final class SessionSecurityFilter extends OncePerRequestFilter {
    private final UserService users;
    private final ObjectMapper mapper;
    SessionSecurityFilter(UserService users, ObjectMapper mapper) { this.users = users; this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                var identity = users.session(jwt.getToken().getSubject(), jwt.getToken().getClaimAsString("sid"));
                request.setAttribute(UserController.IDENTITY, identity);
                var verified = new JwtAuthenticationToken(jwt.getToken(), List.of(new SimpleGrantedAuthority("ROLE_" + identity.user().role().toUpperCase(java.util.Locale.ROOT))));
                SecurityContextHolder.getContext().setAuthentication(verified);
                String path = request.getRequestURI().substring(request.getContextPath().length());
                boolean selfService = ("GET".equals(request.getMethod()) && path.equals("/api/v1/auth/me"))
                    || ("POST".equals(request.getMethod()) && (path.equals("/api/v1/auth/change-password") || path.equals("/api/v1/auth/logout")));
                if (identity.user().mustChangePassword() && !selfService) throw new UserFailure(403, 40310, "Password change required");
                boolean adminRoute = path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/");
                if (adminRoute && !identity.user().role().equals("admin")) throw UserFailure.denied();
                // Admin 白名单只开放账号元数据、本人认证与系统信息，其余未来内容端点也默认拒绝。
                boolean userManagement = (path.equals("/api/v1/admin/users") && (request.getMethod().equals("GET") || request.getMethod().equals("POST")))
                    || (path.matches("/api/v1/admin/users/[^/]+/status") && request.getMethod().equals("PATCH"))
                    || (path.matches("/api/v1/admin/users/[^/]+/reset-password") && request.getMethod().equals("POST"));
                if (identity.user().role().equals("admin") && !selfService && !userManagement && !path.equals("/api/v1/system/info")
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
