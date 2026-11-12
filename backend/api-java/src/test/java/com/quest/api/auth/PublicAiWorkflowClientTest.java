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
                    default->"event:delta\ndata:{\"text\":\"讲解完成\"}\n\nevent:workflow_result\ndata:{\"kind\":\"explain\"}\n\nevent:done\ndata:{}\n\n";
                };
                byte[] bytes=stream.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}
        });server.start();
        try{
            var client=new PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",audit);var payload=Map.of("grant","PRIVATE_GRANT","context",Map.of("message","问题"));var caller=new PublicAiClient.Caller(1L,"workflow");
            var changed=new AtomicInteger();var control=new CoachStreamControl();control.onWorkflowState(changed::incrementAndGet);
            assertThat(client.coachWorkflow(payload,caller,x->{},control).path("kind").asText()).isEqualTo("state_only");assertThat(changed.get()).isEqualTo(1);
            assertThatThrownBy(()->client.coachWorkflow(payload,caller,x->{},new CoachStreamControl())).isInstanceOf(UserFailure.class);
            var text=new StringBuilder();assertThat(client.coachWorkflow(payload,caller,text::append,new CoachStreamControl()).path("kind").asText()).isEqualTo("explain");assertThat(text.toString()).isEqualTo("讲解完成");
            var requests=org.mockito.ArgumentCaptor.forClass(Object.class);org.mockito.Mockito.verify(audit,org.mockito.Mockito.times(3)).record(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("ai.coach_workflow"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("workflow"),requests.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.isNull());
            for(Object request:requests.getAllValues())assertThat(json.valueToTree(request).toString()).contains("context").doesNotContain("grant","PRIVATE_GRANT");
        }finally{server.stop(0);}
    }
}
