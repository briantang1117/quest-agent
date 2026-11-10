package com.quest.api.auth;

import com.quest.api.coach.AiCallAudit;
import com.quest.api.publicbank.PublicAiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicAiMaterialClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiCallAudit audit = mock(AiCallAudit.class);
    private final PublicAiClient.Caller caller = new PublicAiClient.Caller(1L, "material-test");
    private final Map<String, Object> payload = Map.of("scope", "student", "messages",
        java.util.List.of(Map.of("id", "m1", "role", "user", "text", "PRIVATE_CONVERSATION")));

    private AnnotationConfigApplicationContext context(HttpServer server) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
            Map.of("quest.materials.ai-timeout", "500ms")));
        context.registerBean(PublicAiClient.class, () -> new PublicAiClient(json,
            "http://127.0.0.1:" + server.getAddress().getPort(), "token",
            "secret-secret-secret-secret-secret", audit));
        context.refresh();
        return context;
    }

    private void verifyAudit(JsonNode result, boolean failed) throws Exception {
        var invocation = mockingDetails(audit).getInvocations();
        assertThat(invocation).hasSize(1);
        var args = invocation.iterator().next().getArguments();
        assertThat(args[0]).isEqualTo(1L);
        assertThat(args[1]).isEqualTo("ai.material_generate");
        assertThat(args[3]).isEqualTo("material-test");
        JsonNode input = (JsonNode) args[4], output = (JsonNode) args[5];
        byte[] raw = json.writeValueAsBytes(payload);
        assertThat(input.size()).isEqualTo(2);
        assertThat(input.path("bytes").asInt()).isEqualTo(raw.length);
        assertThat(input.path("sha256").asText()).isEqualTo(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw)));
        assertThat(input.toString()).doesNotContain("PRIVATE_CONVERSATION");
        if (result != null) {
            byte[] response = json.writeValueAsBytes(result);
            assertThat(output.size()).isEqualTo(2);
            assertThat(output.path("bytes").asInt()).isEqualTo(response.length);
            assertThat(output.path("sha256").asText()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(response)));
            assertThat(output.toString()).doesNotContain("PRIVATE_MARKDOWN");
        } else assertThat(output.isEmpty()).isTrue();
        if (failed) assertThat(args[6]).isInstanceOf(Throwable.class);
        else assertThat(args[6]).isNull();
        assertThat(args[8]).isEqualTo(1);
        verify(audit).record(eq(1L), eq("ai.material_generate"), isNull(), eq("material-test"),
            same(args[4]), same((JsonNode) args[5]), same((Throwable) args[6]), anyLong(), eq(1), isNull());
        verifyNoMoreInteractions(audit);
    }

    @Test void deadlineCoversResponseBodyAfterHeadersAndReleasesCaller() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var headers = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        server.createContext("/internal/material-generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var context = context(server); var worker = Executors.newSingleThreadExecutor()) {
            var client = context.getBean(PublicAiClient.class);
            var result = worker.submit(() -> {
                try { client.generateMaterial(payload, caller); return (Throwable) null; }
                catch (RuntimeException failure) { return failure; }
            });
            try {
                assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
                Throwable failure = result.get(2, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(UserFailure.class);
                assertThat(((UserFailure) failure).code()).isEqualTo(50401);
                assertThat(calls.get()).isEqualTo(1);
                verifyAudit(null, true);
                assertThat(worker.submit(() -> Thread.currentThread().isInterrupted()).get())
                    .as("资料截止时间不得污染复用工作线程的中断状态").isFalse();
            } finally { release.countDown(); }
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void fullLargeMaterialIsReturnedAndSuccessAuditContainsOnlyDigests() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String markdown = "PRIVATE_MARKDOWN" + "文".repeat(99980);
        var result = json.createObjectNode().put("title", "复习").put("markdown", markdown);
        byte[] response = json.writeValueAsBytes(Map.of("code", 0, "data", result));
        server.createContext("/internal/material-generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            // 保留现有手工构造入口，不需要新增必填参数。
            var client = new PublicAiClient(json,
                "http://127.0.0.1:" + server.getAddress().getPort(), "token",
                "secret-secret-secret-secret-secret", audit);
            assertThat(client.generateMaterial(payload, caller)).isEqualTo(result);
            verifyAudit(result, false);
        } finally { server.stop(0); }
    }

    @Test void oversizedMaterialFailsWithoutRetryAndAuditsOnlyOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/internal/material-generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = ("PRIVATE_MARKDOWN" + "x".repeat(1024 * 1024)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); }
            finally { exchange.close(); }
        });
        server.start();
        try (var context = context(server)) {
            assertThatThrownBy(() -> context.getBean(PublicAiClient.class)
                .generateMaterial(payload, caller)).isInstanceOf(UserFailure.class);
            assertThat(calls.get()).isEqualTo(1);
            verifyAudit(null, true);
        } finally { server.stop(0); }
    }
}
