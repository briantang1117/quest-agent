package com.quest.api.auth;

import com.quest.api.coach.AiCallAudit;
import com.quest.api.publicbank.PublicAiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MaterialPdfClientTest {
    @Test void decodesPdfRejectsInvalidOutputAndAuditsOnlyDigest()throws Exception{
        var json=new ObjectMapper();var audit=mock(AiCallAudit.class);var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] pdf="%PDF-1.4 fixture".getBytes(StandardCharsets.US_ASCII);
        server.createContext("/internal/material-pdf",exchange->{
            try{
                var payload=json.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(payload.path("markdown").asText()).isEqualTo("PRIVATE_MARKDOWN");
                assertThat(exchange.getRequestHeaders().getFirst("X-Signature")).matches("[0-9a-f]{64}");
                var body=json.writeValueAsBytes(Map.of("code",0,"data",Map.of("pdf_base64",count.incrementAndGet()==1?Base64.getEncoder().encodeToString(pdf):"invalid-base64")));
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}
        });server.start();
        try{
            var client=new PublicAiClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),"token","secret-secret-secret-secret-secret",audit);
            var caller=new PublicAiClient.Caller(1L,"pdf");
            assertThat(client.materialPdf("私人标题","PRIVATE_MARKDOWN",caller)).isEqualTo(pdf);
            assertThatThrownBy(()->client.materialPdf("私人标题","PRIVATE_MARKDOWN",caller)).isInstanceOf(UserFailure.class);
            var requests=org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(audit,times(2)).record(eq(1L),eq("material.pdf_export"),isNull(),eq("pdf"),requests.capture(),any(),any(),anyLong(),eq(0),isNull());
            for(Object request:requests.getAllValues())assertThat(json.valueToTree(request).toString()).contains("sha256","bytes").doesNotContain("PRIVATE_MARKDOWN","私人标题");
        }finally{server.stop(0);}
    }
}
