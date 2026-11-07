package com.quest.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "quest.traceId";
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader("X-Trace-Id");
        String trace = incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}") ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, trace);
        response.setHeader("X-Trace-Id", trace);
        String previous = MDC.get("traceId");
        try { MDC.put("traceId", trace); chain.doFilter(request, response); }
        finally { if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous); }
    }
    public static String traceId(HttpServletRequest request) { return (String) request.getAttribute(ATTRIBUTE); }
}
