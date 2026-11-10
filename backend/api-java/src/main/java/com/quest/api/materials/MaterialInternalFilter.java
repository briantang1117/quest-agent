package com.quest.api.materials;

import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 仅允许精确的资料内部POST；签名绑定原始正文，并限制nonce缓存容量。 */
public final class MaterialInternalFilter extends OncePerRequestFilter {
    public static final Set<String> PATHS=Set.of("/internal/materials/outbox/claim","/internal/materials/outbox/ack","/internal/materials/execute");
    public static final String VERIFIED=MaterialInternalFilter.class.getName();
    private final String token,secret;private final ObjectMapper json;
    private final Map<String,Long> nonces=new HashMap<>();
    public MaterialInternalFilter(String token,String secret,ObjectMapper json){this.token=token;this.secret=secret;this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !PATHS.contains(r.getRequestURI().substring(r.getContextPath().length()));}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        byte[] body=r.getInputStream().readNBytes(16385);boolean accepted=false;
        try{
            if(!r.getMethod().equals("POST")||body.length>16384||token.isBlank()||secret.length()<32)throw new IllegalArgumentException();
            String stamp=r.getHeader("X-Timestamp"),nonce=r.getHeader("X-Nonce"),signature=r.getHeader("X-Signature"),provided=r.getHeader("X-Service-Token");
            long now=Instant.now().getEpochSecond(),time=Long.parseLong(stamp);
            // 显式比较避免Math.abs(now-time)在极端long值下溢出绕过时间窗。
            if(time<now-300||time>now+300||nonce==null||!nonce.matches("[A-Za-z0-9_-]{1,128}")||signature==null||!signature.matches("[a-f0-9]{64}")||provided==null)throw new IllegalArgumentException();
            if(!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
            String path=r.getRequestURI().substring(r.getContextPath().length());
            String message=String.join("\n",stamp,nonce,"POST",path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)),HexFormat.of().parseHex(signature)))throw new IllegalArgumentException();
            synchronized(nonces){nonces.entrySet().removeIf(e->e.getValue()<now);if(nonces.size()>=10000||nonces.containsKey(nonce))throw new IllegalArgumentException();nonces.put(nonce,time+300);}
            accepted=true;
        }catch(Exception invalid){/* 所有鉴权失败统一输出，不回显签名、正文或异常。 */}
        if(!accepted){response.setStatus(401);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");json.writeValue(response.getOutputStream(),ApiResponse.error(40101,"内部请求认证失败或已重放",TraceFilter.traceId(r)));return;}
        r.setAttribute(VERIFIED,true);
        chain.doFilter(new HttpServletRequestWrapper(r){
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
}
