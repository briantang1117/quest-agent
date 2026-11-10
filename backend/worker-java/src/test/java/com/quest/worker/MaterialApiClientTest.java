package com.quest.worker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MaterialApiClientTest {
    private static final String TOKEN = "test-only-service-token";
    private static final String SECRET = "test-only-hmac-secret-at-least-32-bytes";
    private final List<String> nonces = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private MaterialApiClient client;
    private String response = "{\"code\":0,\"message\":\"OK\",\"data\":{\"status\":\"ready\"},\"traceId\":\"test-trace\"}";
    private int status = 200;
    private String capturedBody;
    private String signature;
    private String timestamp;
    private String nonce;
    private String token;
    private String path;

    private void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/materials/", exchange -> {
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            signature = exchange.getRequestHeaders().getFirst("X-Signature");
            timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
            nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
            token = exchange.getRequestHeaders().getFirst("X-Service-Token");
            path = exchange.getRequestURI().getPath();
            nonces.add(nonce);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        client = new MaterialApiClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, SECRET, JsonMapper.builder().build());
    }

    @AfterEach void close() { if (client != null) client.close(); if (server != null) server.stop(0); }

    @Test void signsExactRawBodyAndUsesFreshNonceAndLongDeadline() throws Exception {
        start();
        var event = new MaterialApi.TaskEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertThat(client.execute(event)).isEqualTo("ready");
        assertThat(token).isEqualTo(TOKEN);
        assertThat(path).isEqualTo("/internal/materials/execute");
        String canonical = String.join("\n", timestamp, nonce, "POST", path,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(capturedBody.getBytes(StandardCharsets.UTF_8))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(signature).isEqualTo(HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        assertThat(JsonMapper.builder().build().readTree(capturedBody).propertyNames()).containsExactlyInAnyOrder("taskId", "generation", "eventId");
        assertThat(MaterialApiClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(700));
        client.execute(event);
        assertThat(nonces).doesNotHaveDuplicates();
    }

    @Test void rejectsMalformedOrBusinessFailureEnvelopesRatherThanAcknowledging() throws Exception {
        start();
        var event = new MaterialApi.TaskEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        for (String invalid : List.of("{}", "{\"code\":0,\"data\":{\"status\":\"ready\"}}",
                "{\"code\":\"0\",\"message\":\"OK\",\"traceId\":\"t\",\"data\":{\"status\":\"ready\"}}",
                "{\"code\":1,\"message\":\"error\",\"traceId\":\"t\",\"data\":{\"status\":\"ready\"}}",
                "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":{\"status\":\"queued\"}}",
                "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":{\"status\":\"ready\"}} {}")) {
            response = invalid;
            assertThatThrownBy(() -> client.execute(event)).isInstanceOf(Exception.class);
        }
        status = 503;
        assertThatThrownBy(() -> client.execute(event)).isInstanceOf(Exception.class);
    }

    @Test void claimIsEmptyObjectAndRejectsOversizedBatchAndPrivatePayload() throws Exception {
        start();
        response = "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":{\"list\":[]}}";
        assertThat(client.claim()).isEmpty();
        assertThat(capturedBody).isEqualTo("{}");
        String id = UUID.randomUUID().toString();
        String payload = "{\\\"taskId\\\":\\\"" + id + "\\\",\\\"generation\\\":\\\"" + id + "\\\",\\\"eventId\\\":\\\"" + id + "\\\",\\\"privateText\\\":\\\"secret\\\"}";
        String item = "{\"eventId\":\"" + id + "\",\"leaseToken\":\"lease\",\"taskId\":\"" + id + "\",\"generation\":\"" + id + "\",\"topic\":\"quest-learning-materials\",\"tag\":\"generate\",\"msgKey\":\"key\",\"payload\":\"" + payload + "\"}";
        response = "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":{\"list\":[" + item + "]}}";
        assertThatThrownBy(() -> client.claim()).isInstanceOf(Exception.class);
        response = "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":{\"list\":[" + String.join(",", java.util.Collections.nCopies(9, "{}")) + "]}}";
        assertThatThrownBy(() -> client.claim()).isInstanceOf(Exception.class);
    }

    @Test void validClaimPreservesUuidGenerationAndRejectsMismatchedPayload() throws Exception {
        start();
        var mapper = JsonMapper.builder().build();
        var event = new MaterialApi.TaskEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("taskId", event.taskId()); item.put("generation", event.generation()); item.put("eventId", event.eventId());
        item.put("topic", "quest-learning-materials"); item.put("tag", "generate"); item.put("leaseToken", UUID.randomUUID().toString());
        item.put("msgKey", event.taskId()); item.put("payload", mapper.writeValueAsString(event));
        response = mapper.writeValueAsString(java.util.Map.of("code", 0, "message", "OK", "traceId", "t", "data", java.util.Map.of("list", List.of(item))));
        assertThat(client.claim()).singleElement().satisfies(claim -> assertThat(claim.task()).isEqualTo(event));
        item.put("payload", mapper.writeValueAsString(new MaterialApi.TaskEvent(event.taskId(), UUID.randomUUID().toString(), event.eventId())));
        response = mapper.writeValueAsString(java.util.Map.of("code", 0, "message", "OK", "traceId", "t", "data", java.util.Map.of("list", List.of(item))));
        assertThatThrownBy(() -> client.claim()).isInstanceOf(Exception.class);
    }

    @Test void responseBodyIsBounded() throws Exception {
        start();
        response = " ".repeat(128 * 1024 + 1);
        assertThatThrownBy(() -> client.claim()).isInstanceOf(Exception.class);
    }

    @Test void deadlineIncludesStalledBodyAfterHeaders() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/materials/outbox/claim", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var stream = exchange.getResponseBody()) {
                stream.write('{'); stream.flush();
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        server.start();
        client = new MaterialApiClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, SECRET,
                JsonMapper.builder().build(), Duration.ofMillis(150));
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> client.claim()).satisfies(failure -> {
                // HttpRequest 自身与完整响应 future 的期限可能先后触发，均须是明确超时。
                Throwable timeout = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
                assertThat(timeout).isInstanceOfAny(java.util.concurrent.TimeoutException.class, java.net.http.HttpTimeoutException.class);
            });
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
    }

    @Test void ackRequiresNullDataAndCarriesLease() throws Exception {
        start();
        var ack = new MaterialApi.OutboxAck(UUID.randomUUID().toString(), "lease-token", true);
        response = "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":null}";
        client.ack(ack);
        assertThat(capturedBody).contains("\"success\":true", "\"leaseToken\":\"lease-token\"");
        response = "{\"code\":0,\"message\":\"OK\",\"traceId\":\"t\",\"data\":{}}";
        assertThatThrownBy(() -> client.ack(ack)).isInstanceOf(Exception.class);
    }
}
