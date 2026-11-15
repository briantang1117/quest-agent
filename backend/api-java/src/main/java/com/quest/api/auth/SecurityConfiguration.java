package com.quest.api.auth;

import tools.jackson.databind.ObjectMapper;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration @EnableMethodSecurity
public class SecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${quest.security.jwt.secret:}") String secret,
            @Value("${quest.security.jwt.issuer:quest-api}") String issuer,
            @Value("${quest.security.jwt.audience:quest-web}") String audience, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local")) && environment.acceptsProfiles(Profiles.of("deployment", "production")))
            throw new IllegalStateException("The local profile must not be combined with deployment or production");
        if (secret.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local"))) throw new IllegalStateException("JWT_SECRET is required outside the local profile");
            // Local is intentionally locked without an explicitly configured signing secret.
            return token -> { throw new BadJwtException("JWT verification is not configured"); };
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank() || !jwt.getAudience().contains(audience))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required JWT claims are missing or invalid", null));
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, UserService users,com.quest.api.admin.AdminControlService controls,
            @Value("${INTERNAL_SERVICE_TOKEN:}") String internalToken,@Value("${INTERNAL_HMAC_SECRET:}") String internalSecret) throws Exception {
        return http.csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .requestCache(cache -> cache.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                // 仅精确POST由各自前置HMAC过滤器验证，其余内部接口仍默认拒绝。
                .requestMatchers(HttpMethod.POST, com.quest.api.materials.MaterialInternalFilter.PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.POST, com.quest.api.coach.CoachWorkflowInternalFilter.PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/internal/**", "/api/v1/internal/**").denyAll().anyRequest().authenticated())
            .addFilterBefore(new com.quest.api.materials.MaterialInternalFilter(internalToken,internalSecret,mapper), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .addFilterBefore(new com.quest.api.coach.CoachWorkflowInternalFilter(internalToken,internalSecret,mapper), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new SessionSecurityFilter(users, mapper,controls), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> writeError(mapper, req, res, 401, 40100, "Authentication required"))
                .accessDeniedHandler((req, res, ex) -> writeError(mapper, req, res, 403, 40300, "Access denied")))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}).authenticationEntryPoint((req, res, ex) -> writeError(mapper, req, res, 401, 40100, "Invalid or expired bearer token"))
                .accessDeniedHandler((req, res, ex) -> writeError(mapper, req, res, 403, 40300, "Access denied")))
            .build();
    }
    private static void writeError(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message, TraceFilter.traceId(request)));
    }
}
