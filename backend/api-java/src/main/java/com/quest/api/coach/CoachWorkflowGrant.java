package com.quest.api.coach;

import com.quest.api.auth.UserFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Java独有密钥签发的单轮能力凭证，不能复用内部服务共享密钥签发。 */
@Component
public final class CoachWorkflowGrant {
    public record Claims(String userId,String loginSession,UUID sessionId,UUID requestId,String hash,String action,String questionId,List<String> keys,long expires) {}
    private final ObjectMapper json;private final byte[] secret;
    public CoachWorkflowGrant(ObjectMapper json,@Value("${quest.security.jwt.secret:}") String secret){this.json=json;this.secret=secret.getBytes(StandardCharsets.UTF_8);}
    public String issue(Claims claims){
        if(secret.length<32)throw new UserFailure(503,50321,"编排授权未配置");
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }
    public Claims verify(String grant){
        try{
            if(secret.length<32||grant==null||grant.length()>8192)throw new IllegalArgumentException();
            String[] parts=grant.split("\\.",-1);if(parts.length!=2)throw new IllegalArgumentException();
            if(!MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            Claims claims=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Claims.class);
            long now=Instant.now().getEpochSecond();
            if(claims.expires()<=now||claims.expires()>now+180||claims.userId()==null||claims.loginSession()==null||claims.sessionId()==null||claims.requestId()==null||claims.hash()==null||!claims.hash().matches("[a-f0-9]{64}")||!Set.of("answer","next","chat").contains(claims.action()))throw new IllegalArgumentException();
            return claims;
        }catch(Exception invalid){throw new UserFailure(403,40331,"编排授权无效或已过期");}
    }
    private byte[] sign(String body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(("quest-coach-workflow-v1\n"+body).getBytes(StandardCharsets.UTF_8));}catch(Exception impossible){throw new IllegalStateException("编排签名不可用");}}
}
