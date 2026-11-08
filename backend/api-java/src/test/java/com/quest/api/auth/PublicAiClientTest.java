package com.quest.api.auth;

import com.sun.net.httpserver.HttpServer;
import com.quest.api.publicbank.PublicAiClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PublicAiClientTest {
    /** 本测试只关心签名与不重试，审计写库用 disabled 模式跳过。 */
    private static com.quest.api.coach.AiCallAudit audit(){
        return new com.quest.api.coach.AiCallAudit(new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(org.springframework.jdbc.core.JdbcTemplate.class),new ObjectMapper(),"disabled");
    }
    @Test void signsExactBodyAndPathAndDoesNotRetryProviderFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var json=new ObjectMapper();
        server.createContext("/internal/public-import",exchange->{
            try {
                calls.incrementAndGet();byte[] body=exchange.getRequestBody().readAllBytes();
                var headers=exchange.getRequestHeaders();
                String message=headers.getFirst("X-Timestamp")+"\n"+headers.getFirst("X-Nonce")+"\nPOST\n/internal/public-import\n"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("secret-secret-secret-secret-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                assertThat(headers.getFirst("X-Signature")).isEqualTo(HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
                assertThat(headers.getFirst("X-Service-Token")).isEqualTo("token");
                assertThat(json.readTree(body).get("scope").asText()).isEqualTo("admin");
                byte[] response="{\"code\":0,\"data\":{\"draft\":null,\"warnings\":[\"unrecognized_reference_answer\"],\"provenance\":{}}}".getBytes(StandardCharsets.UTF_8);
                int status=calls.get()==1?200:429;
                exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);exchange.close();
            } catch(Exception failure){throw new RuntimeException(failure);}
        });
        server.start();
        try {
            var client=new PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",audit());
            assertThat(client.extract("合成材料").path("warnings").get(0).asText()).isEqualTo("unrecognized_reference_answer");
            assertThatThrownBy(()->client.extract("合成材料")).isInstanceOf(UserFailure.class);
            assertThat(calls.get()).isEqualTo(2);
        } finally {server.stop(0);}
    }
}
