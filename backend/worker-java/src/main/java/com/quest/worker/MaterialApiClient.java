package com.quest.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 原始 UTF-8 请求体签名；总期限包含响应体读取，不进行 HTTP 自动业务重试。 */
final class MaterialApiClient implements MaterialApi, AutoCloseable {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(700);
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final Set<String> EVENT_FIELDS = Set.of("taskId", "generation", "eventId");
    private final URI base;
    private final String token;
    private final String secret;
    private final JsonMapper json;
    private final HttpClient http;
    private final Duration timeout;

    MaterialApiClient(String base, String token, String secret, JsonMapper json) {
        this(base, token, secret, json, REQUEST_TIMEOUT);
    }

    MaterialApiClient(String base, String token, String secret, JsonMapper json, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(REQUEST_TIMEOUT) > 0)
            throw new IllegalArgumentException("内部 API 超时配置无效");
        this.timeout = timeout;
        this.base = URI.create(base);
        if (!Set.of("http", "https").contains(this.base.getScheme()) || this.base.getHost() == null
                || this.base.getUserInfo() != null || this.base.getQuery() != null || this.base.getFragment() != null
                || !Set.of("", "/").contains(this.base.getPath())) throw new IllegalArgumentException("内部 API 地址无效");
        if (token == null || token.isBlank() || secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("资料 Worker 内部认证未配置");
        this.token = token;
        this.secret = secret;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public List<OutboxEvent> claim() throws Exception {
        JsonNode data = post("/internal/materials/outbox/claim", java.util.Map.of());
        JsonNode list = data.path("list");
        if (!list.isArray() || list.size() > 8) throw invalid();
        List<OutboxEvent> events = new ArrayList<>();
        for (JsonNode item : list) {
            TaskEvent task = readTask(item, false);
            String payload = text(item, "payload");
            if (!readTask(json.readTree(payload), true).equals(task)) throw invalid();
            String topic = text(item, "topic"), tag = text(item, "tag");
            if (!topic.equals(MaterialMqWorker.TOPIC) || !tag.equals(MaterialMqWorker.TAG)) throw invalid();
            events.add(new OutboxEvent(task.eventId(), text(item, "leaseToken"), task.taskId(), task.generation(),
                    topic, tag, text(item, "msgKey"), payload));
        }
        return List.copyOf(events);
    }

    @Override public void ack(OutboxAck ack) throws Exception {
        if (!post("/internal/materials/outbox/ack", ack).isNull()) throw invalid();
    }

    @Override public String execute(TaskEvent event) throws Exception {
        String status = text(post("/internal/materials/execute", event), "status");
        if (!Set.of("ready", "failed", "processing", "ignored").contains(status)) throw invalid();
        return status;
    }

    static TaskEvent readTask(JsonNode node, boolean exact) throws IOException {
        if (!node.isObject() || (exact && !Set.copyOf(node.propertyNames()).equals(EVENT_FIELDS))) throw invalid();
        try { return new TaskEvent(text(node, "taskId"), text(node, "generation"), text(node, "eventId")); }
        catch (IllegalArgumentException e) { throw invalid(); }
    }

    private JsonNode post(String path, Object payload) throws Exception {
        byte[] bytes = json.writeValueAsBytes(payload);
        String timestamp = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
        String canonical = String.join("\n", timestamp, nonce, "POST", path,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json").header("X-Service-Token", token)
                .header("X-Timestamp", timestamp).header("X-Nonce", nonce)
                .header("X-Signature", HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        var pending = http.sendAsync(request, ignored -> new LimitedBody());
        HttpResponse<byte[]> response;
        try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception e) { pending.cancel(true); throw e; }
        if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw invalid();
        JsonNode envelope = json.readTree(response.body());
        if (envelope == null || !envelope.isObject() || !envelope.path("code").isIntegralNumber()
                || !envelope.path("code").canConvertToInt() || envelope.path("code").intValue() != 0
                || !envelope.path("message").isString() || envelope.path("message").asString().isBlank()
                || !envelope.path("traceId").isString() || envelope.path("traceId").asString().isBlank()
                || !envelope.has("data")) throw invalid();
        return envelope.path("data");
    }

    private static String text(JsonNode node, String key) throws IOException {
        if (!node.path(key).isString() || node.path(key).asString().isBlank()) throw invalid();
        return node.path(key).asString();
    }
    private static IOException invalid() { return new IOException("资料内部 API 响应无效"); }

    @Override public void close() { http.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(invalid()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
