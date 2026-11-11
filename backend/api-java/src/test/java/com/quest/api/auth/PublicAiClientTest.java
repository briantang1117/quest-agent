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
    @Test void streamRequiresDoneAndAuditsFailureAndSuccessExactlyOnce()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var json=new ObjectMapper();
        var audit=org.mockito.Mockito.mock(com.quest.api.coach.AiCallAudit.class);
        server.createContext("/internal/coach-explain",exchange->{
            try{
                exchange.getRequestBody().readAllBytes();int n=calls.incrementAndGet();
                String stream="event: delta\ndata: {\"text\":\"讲解\"}\n\n";
                if(n==2)stream+="event: suggestions\ndata: {\"items\":[]}\n\nevent: done\ndata: {}\n\n";
                byte[] bytes=stream.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            }catch(Exception failure){throw new RuntimeException(failure);}
        });server.start();
        try{
            var client=new PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",audit);
            var caller=new PublicAiClient.Caller(1L,"test-stream");
            assertThatThrownBy(()->client.coachExplain(java.util.Map.of("scope","student"),caller,x->{},new com.quest.api.coach.CoachStreamControl())).isInstanceOf(UserFailure.class);
            assertThat(client.coachExplain(java.util.Map.of("scope","student"),caller,x->{},new com.quest.api.coach.CoachStreamControl()).path("items").isArray()).isTrue();
            var responses=org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
            var failures=org.mockito.ArgumentCaptor.forClass(Throwable.class);
            org.mockito.Mockito.verify(audit,org.mockito.Mockito.times(2)).record(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("ai.coach_explain"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("test-stream"),org.mockito.ArgumentMatchers.any(),responses.capture(),failures.capture(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.isNull());
            assertThat(failures.getAllValues().get(0)).isNotNull();assertThat(failures.getAllValues().get(1)).isNull();
            assertThat(responses.getAllValues().get(1).path("reply").asText()).isEqualTo("讲解");
            assertThat(calls.get()).isEqualTo(2);
        }finally{server.stop(0);}
    }
    @Test void realUvicornAcceptsJavaRouteAndStreamWithoutUpgradeFailures()throws Exception{
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(!java.nio.file.Files.isDirectory(root.resolve("backend/ai-python")))root=root.getParent();
        var python=root.resolve("backend/ai-python/.venv/bin/python");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(python),"跨语言测试需要本地Python虚拟环境");
        int port;try(var probe=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=probe.getLocalPort();}
        var log=java.nio.file.Files.createTempFile("quest-uvicorn-local-",".log");
        var process=new ProcessBuilder(python.toString(),root.resolve("backend/ai-python/tests/local_coach_server.py").toString(),String.valueOf(port))
            .directory(root.resolve("backend/ai-python").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);boolean ready=false;
            while(System.nanoTime()<deadline&&process.isAlive()){
                try(var socket=new java.net.Socket("127.0.0.1",port)){ready=true;break;}catch(java.io.IOException ignored){Thread.sleep(50);}
            }
            assertThat(ready).as(java.nio.file.Files.readString(log)).isTrue();
            var client=new PublicAiClient(new ObjectMapper(),"http://127.0.0.1:"+port,"test-token-for-internal-service-123456","test-hmac-secret-at-least-32-characters",audit());
            var payload=java.util.Map.of("scope","student","subject","vocational_aptitude","message","邯郸学步是什么意思","history",java.util.List.of());
            assertThat(client.coachRoute(payload,new PublicAiClient.Caller(1L,"local")).path("action").asText()).isEqualTo("chat");
            var text=new StringBuilder();
            client.coachExplain(payload,new PublicAiClient.Caller(1L,"local"),text::append,new com.quest.api.coach.CoachStreamControl());
            assertThat(text.toString()).isEqualTo("邯郸学步比喻盲目模仿。");
            assertThat(java.nio.file.Files.readString(log)).doesNotContain("Unsupported upgrade request");
        }finally{process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();java.nio.file.Files.deleteIfExists(log);}
    }
    @Test void streamConnectionFailureStillWritesAudit()throws Exception{
        var audit=org.mockito.Mockito.mock(com.quest.api.coach.AiCallAudit.class);
        int port;try(var probe=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=probe.getLocalPort();}
        var client=new PublicAiClient(new ObjectMapper(),"http://127.0.0.1:"+port,"token","secret-secret-secret-secret-secret",audit);
        assertThatThrownBy(()->client.coachExplain(java.util.Map.of(),new PublicAiClient.Caller(1L,"t"),x->{},new com.quest.api.coach.CoachStreamControl())).isInstanceOf(UserFailure.class);
        org.mockito.Mockito.verify(audit).record(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("ai.coach_explain"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("t"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.notNull(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.isNull());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"search_web","fetch_page"})
    void acceptsToolStatusAndStructuredSources(String tool)throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var toolAudit=org.mockito.Mockito.mock(com.quest.api.coach.AiCallAudit.class);
        server.createContext("/internal/coach-explain",exchange->{
            exchange.getRequestBody().readAllBytes();
            String wire="event: tool_status\ndata: {\"tool\":\"search_web\",\"status\":\"running\"}\n\n"
                +"event: delta\ndata: {\"text\":\"解释\"}\n\n"
                +"event: sources\ndata: {\"items\":[{\"tool\":\"search_web\",\"title\":\"公开资料\",\"url\":\"https://example.com/reference\",\"retrieved_at\":\"2026-11-11T00:00:00Z\"}]}\n\n"
                +"event: tool_audit\ndata: {\"tool\":\"search_web\",\"parameter_hash\":\""+"0".repeat(64)+"\",\"result_count\":1,\"duration_ms\":12,\"failure\":\"ok\"}\n\n"
                +"event: done\ndata: {}\n\n";
            byte[] body=wire.replace("search_web",tool).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var client=new PublicAiClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",toolAudit);
            var statuses=new java.util.ArrayList<tools.jackson.databind.JsonNode>();
            var result=client.coachExplain(java.util.Map.of(),new PublicAiClient.Caller(1L,"tools"),x->{},statuses::add,new com.quest.api.coach.CoachStreamControl());
            org.mockito.Mockito.verify(toolAudit).record(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq(tool.equals("search_web")?"ai.tool_search":"ai.tool_fetch"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("tools"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(12L),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.isNull());
            assertThat(statuses).hasSize(1);
            assertThat(result.path("sources").get(0).path("title").asText()).isEqualTo("公开资料");
        }finally{server.stop(0);}
    }

}
