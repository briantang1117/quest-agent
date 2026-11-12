package com.quest.api.auth;

import com.quest.api.coach.CoachWorkflowInternalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CoachWorkflowInternalFilterTest {
    final String token="service-token",secret="callback-hmac-secret-at-least-32-bytes";
    MockHttpServletRequest signed(String nonce)throws Exception{
        var request=new MockHttpServletRequest("POST","/internal/coach/workflow-step");byte[] body="{}".getBytes(StandardCharsets.UTF_8);request.setContent(body);
        String stamp=Long.toString(Instant.now().getEpochSecond());
        String text=String.join("\n",stamp,nonce,"POST",request.getRequestURI(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        request.addHeader("X-Service-Token",token);request.addHeader("X-Timestamp",stamp);request.addHeader("X-Nonce",nonce);request.addHeader("X-Signature",HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))));return request;
    }
    @Test void acceptsExactSignatureRejectsNonceReplayDuplicateHeadersAndUnsignedQuery()throws Exception{
        var filter=new CoachWorkflowInternalFilter(token,secret,new ObjectMapper());
        var good=signed("first");var accepted=new MockHttpServletResponse();filter.doFilter(good,accepted,new MockFilterChain());assertThat(good.getAttribute(CoachWorkflowInternalFilter.VERIFIED)).isEqualTo(true);
        var replay=new MockHttpServletResponse();filter.doFilter(signed("first"),replay,new MockFilterChain());assertThat(replay.getStatus()).isEqualTo(401);
        for(String header:List.of("X-Service-Token","X-Timestamp","X-Nonce","X-Signature")){
            var request=signed(UUID.randomUUID().toString());request.addHeader(header,request.getHeader(header));var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(401);
        }
        var query=signed("query");query.setQueryString("user=other");var rejected=new MockHttpServletResponse();filter.doFilter(query,rejected,new MockFilterChain());assertThat(rejected.getStatus()).isEqualTo(401);
    }
}
