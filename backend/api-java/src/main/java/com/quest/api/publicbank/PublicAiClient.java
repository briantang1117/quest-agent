package com.quest.api.publicbank;

import com.quest.api.auth.UserFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class PublicAiClient {
    private final ObjectMapper json;
    private final String base,token,secret;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PublicAiClient(ObjectMapper json,@Value("${AI_SERVICE_URL:http://127.0.0.1:8000}") String base,
                          @Value("${INTERNAL_SERVICE_TOKEN:}") String token,@Value("${INTERNAL_HMAC_SECRET:}") String secret){this.json=json;this.base=base;this.token=token;this.secret=secret;}
    public JsonNode extract(String text) { return post("/internal/public-import",Map.of("scope","admin","text",text),256*1024); }
    public JsonNode extractCandidate(String text,JsonNode candidate) { return post("/internal/public-import",Map.of("scope","admin","text",text,"candidate",candidate),256*1024); }
    public JsonNode inspectPdf(byte[] pdf) { return post("/internal/pdf-inspect",Map.of("scope","admin","pdf_base64",Base64.getEncoder().encodeToString(pdf)),2*1024*1024); }
    private JsonNode post(String path,Object payload,int maxResponse) {
        if(token.isBlank() || secret.length()<32)throw new UserFailure(503,50321,"内部AI服务未配置");
        try {
            URI root=URI.create(base);
            if(!Set.of("http","https").contains(root.getScheme()) || root.getHost()==null || root.getUserInfo()!=null || root.getQuery()!=null || root.getFragment()!=null || (root.getPath()!=null&&!Set.of("","/").contains(root.getPath())))throw new IllegalArgumentException();
            byte[] body=json.writeValueAsBytes(payload);
            String stamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
            String message=String.join("\n",stamp,nonce,"POST",path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            var request=HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(100)).header("Content-Type","application/json")
                .header("X-Service-Token",token).header("X-Timestamp",stamp).header("X-Nonce",nonce).header("X-Signature",HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // 无应用级自动重试；故障不回显上游响应/URL/签名。
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try(var stream=response.body()){raw=stream.readNBytes(maxResponse+1);}
            if(response.statusCode()!=200 || raw.length>maxResponse)throw new IllegalStateException();
            var envelope=json.readTree(raw);
            if(!envelope.path("code").isIntegralNumber() || envelope.path("code").asInt()!=0 || !envelope.path("data").isObject())throw new IllegalStateException();
            return envelope.get("data");
        } catch(InterruptedException failure){Thread.currentThread().interrupt();throw new UserFailure(503,50322,"AI处理已中断，未自动重试");}
        catch(Exception failure){throw new UserFailure(503,50322,"AI处理失败，请核对服务状态后人工处理");}
    }
}
