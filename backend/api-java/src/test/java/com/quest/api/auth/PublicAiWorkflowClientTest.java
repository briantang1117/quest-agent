package com.quest.api.auth;

import com.quest.api.publicbank.PublicAiClient;
import com.quest.api.coach.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PublicAiWorkflowClientTest {
    @Test void requiresWorkflowTerminalAllowsStateOnlyAndRedactsGrantFromEveryAudit()throws Exception{
        var json=new ObjectMapper();var audit=org.mockito.Mockito.mock(AiCallAudit.class);var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/coach-workflow",exchange->{
            try{
                assertThat(json.readTree(exchange.getRequestBody().readAllBytes()).path("grant").asText()).isEqualTo("PRIVATE_GRANT");
                String stream=switch(count.incrementAndGet()){
                    case 1->"event:workflow_state\ndata:{}\n\nevent:workflow_result\ndata:{\"kind\":\"state_only\"}\n\nevent:done\ndata:{}\n\n";
                    case 2->"event:delta\ndata:{\"text\":\"缺少终态\"}\n\nevent:done\ndata:{}\n\n";
                    default->"event:delta\ndata:{\"text\":\"讲解完成\"}\n\nevent:memory\ndata:{\"summary\":\"历史要点\",\"progress\":\"\",\"questions\":\"\",\"plan\":\"\"}\n\nevent:title\ndata:{\"title\":\"概念辨析\"}\n\nevent:workflow_result\ndata:{\"kind\":\"explain\"}\n\nevent:done\ndata:{}\n\n";
                };
                byte[] bytes=stream.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}
        });server.start();
        try{
            var client=new PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",audit);var payload=Map.of("grant","PRIVATE_GRANT","context",Map.of("message","问题"));var caller=new PublicAiClient.Caller(1L,"workflow");
            var changed=new AtomicInteger();var control=new CoachStreamControl();control.onWorkflowState(changed::incrementAndGet);
            assertThat(client.coachWorkflow(payload,caller,x->{},control).path("kind").asText()).isEqualTo("state_only");assertThat(changed.get()).isEqualTo(1);
            assertThatThrownBy(()->client.coachWorkflow(payload,caller,x->{},new CoachStreamControl())).isInstanceOf(UserFailure.class);
            var text=new StringBuilder();var result=client.coachWorkflow(payload,caller,text::append,new CoachStreamControl());assertThat(result.path("kind").asText()).isEqualTo("explain");assertThat(result.path("memory").path("summary").asText()).isEqualTo("历史要点");assertThat(result.path("title").asText()).isEqualTo("概念辨析");assertThat(text.toString()).isEqualTo("讲解完成");
            var requests=org.mockito.ArgumentCaptor.forClass(Object.class);org.mockito.Mockito.verify(audit,org.mockito.Mockito.times(3)).record(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("ai.coach_workflow"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("workflow"),requests.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.isNull());
            for(Object request:requests.getAllValues())assertThat(json.valueToTree(request).toString()).contains("context").doesNotContain("grant","PRIVATE_GRANT");
        }finally{server.stop(0);}
    }

    @Test void privateQuestionAuditContainsOnlyDigestsOnSuccessAndFailure()throws Exception{
        var json=new ObjectMapper();var audit=org.mockito.Mockito.mock(AiCallAudit.class);var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/private-source",exchange->{try{assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).contains("PRIVATE_QUESTION_BYTES");int status=count.incrementAndGet()==1?200:503;byte[] bytes=(status==200?"{\"code\":0,\"data\":{\"text\":\"PRIVATE_ANSWER\"}}":"{\"code\":50360,\"message\":\"PRIVATE_UPSTREAM_FAILURE\"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        try{var client=new PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",audit);var caller=new PublicAiClient.Caller(1L,"private-audit");assertThat(client.privateQuestions("source",Map.of("content","PRIVATE_QUESTION_BYTES"),caller).path("text").asText()).isEqualTo("PRIVATE_ANSWER");assertThatThrownBy(()->client.privateQuestions("source",Map.of("content","PRIVATE_QUESTION_BYTES"),caller)).isInstanceOfSatisfying(UserFailure.class,failure->assertThat(failure.code()).isEqualTo(50360));var inputs=org.mockito.ArgumentCaptor.forClass(Object.class);var outputs=org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);org.mockito.Mockito.verify(audit,org.mockito.Mockito.times(2)).record(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("ai.private_source"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("private-audit"),inputs.capture(),outputs.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.isNull());for(var value:inputs.getAllValues())assertThat(json.valueToTree(value).toString()).contains("sha256","bytes").doesNotContain("PRIVATE_");for(var value:outputs.getAllValues())assertThat(json.valueToTree(value).toString()).doesNotContain("PRIVATE_");}finally{server.stop(0);}
    }
}
