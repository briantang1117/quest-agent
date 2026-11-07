package com.quest.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 仅精确可信代理可提供单值专用来源头；默认完全忽略转发头。 */
@Component
public final class LoginSource {
    private final Set<String> proxies;
    public LoginSource(@Value("${quest.security.login.trusted-proxies:}") String proxies) {
        this.proxies = Arrays.stream(proxies.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(LoginSource::literal)
            .collect(Collectors.toUnmodifiableSet());
    }
    public String source(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (!proxies.contains(peer)) return peer;
        String header = request.getHeader("X-Quest-Client-IP");
        if (header == null || java.util.Collections.list(request.getHeaders("X-Quest-Client-IP")).size() != 1)
            throw new IllegalArgumentException("Trusted proxy must overwrite client source header");
        return literal(header);
    }
    private static String literal(String value) {
        if (value == null || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+") || (!value.contains(":") && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
            throw new IllegalArgumentException("IP literal required");
        // 含点及单冒号的 IPv4:port 不是 IP 字面量，JDK 解析器可能接受某些非标准形式。
        if (value.indexOf(':') == value.lastIndexOf(':') && value.contains(":")) throw new IllegalArgumentException("Invalid IPv6 literal");
        return InetAddress.ofLiteral(value).getHostAddress();
    }
}
