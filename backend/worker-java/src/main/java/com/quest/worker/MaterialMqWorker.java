package com.quest.worker;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 专用于资料任务的有界 relay/consumer；消息重复由 API 持久化认领去重。 */
final class MaterialMqWorker implements SmartLifecycle {
    static final String TOPIC = "quest-learning-materials";
    static final String TAG = "generate";
    static final String PRODUCER_GROUP = "quest-material-publisher";
    static final String CONSUMER_GROUP = "quest-material-worker";
    private static final Logger LOG = LoggerFactory.getLogger(MaterialMqWorker.class);
    private final MaterialApi api;
    private final DefaultMQProducer producer;
    private final DefaultMQPushConsumer consumer;
    private final JsonMapper json;
    private final java.util.function.Supplier<ScheduledExecutorService> schedulerFactory;
    private volatile boolean running;
    private volatile boolean stopped;
    private ScheduledExecutorService relay;
    private int consecutiveFailures;

    MaterialMqWorker(MaterialApi api, DefaultMQProducer producer, DefaultMQPushConsumer consumer, JsonMapper json) {
        this(api, producer, consumer, json, () -> Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "material-outbox-relay")));
    }

    MaterialMqWorker(MaterialApi api, DefaultMQProducer producer, DefaultMQPushConsumer consumer, JsonMapper json,
                     java.util.function.Supplier<ScheduledExecutorService> schedulerFactory) {
        this.schedulerFactory = schedulerFactory;
        this.api = api;
        this.producer = producer;
        this.consumer = consumer;
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        producer.setProducerGroup(PRODUCER_GROUP);
        // 禁用 TBW102 默认路由回落；Topic 必须经独立授权预先创建。
        producer.setCreateTopicKey(TOPIC);
        producer.setSendMsgTimeout(10_000);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setMaxMessageSize(4096);
        consumer.setConsumerGroup(CONSUMER_GROUP);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(2);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setPullBatchSize(1);
        consumer.setPullThresholdForQueue(4);
        consumer.setPullThresholdForTopic(8);
        consumer.setConsumeTimeout(15);
        consumer.setMaxReconsumeTimes(16);
        consumer.setAwaitTerminationMillisWhenShutdown(710_000);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.registerMessageListener((MessageListenerConcurrently) this::consume);
    }

    @Override public synchronized void start() {
        if (running) return;
        if (stopped) throw new IllegalStateException("资料 Worker 已停止，不能复用客户端");
        try {
            producer.start();
            if (producer.fetchPublishMessageQueues(TOPIC).isEmpty()) throw new IllegalStateException("资料 Topic 尚未准备好");
            consumer.subscribe(TOPIC, TAG);
            consumer.start();
            relay = schedulerFactory.get();
            running = true;
            relay.schedule(this::tick, 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            running = false;
            stopped = true;
            if (relay != null) relay.shutdownNow();
            consumer.shutdown();
            producer.shutdown();
            throw new IllegalStateException("资料 MQ 启动失败，请核验预建 Topic 和运行配置", e);
        }
    }

    private void tick() {
        if (!running) return;
        boolean success = relayOnce();
        consecutiveFailures = success ? 0 : Math.min(consecutiveFailures + 1, 6);
        long delay = success ? 2 : Math.min(60, 1L << consecutiveFailures);
        if (running) {
            try { relay.schedule(this::tick, delay, TimeUnit.SECONDS); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { /* 停止过程中不重新调度。 */ }
        }
    }

    boolean relayOnce() {
        boolean healthy = true;
        try {
            List<MaterialApi.OutboxEvent> batch = api.claim();
            if (batch.size() > 8) throw new IllegalStateException("资料 Outbox 批次超限");
            for (var event : batch) {
                if (stopped || Thread.currentThread().isInterrupted()) return false;
                boolean sent = false;
                try {
                    if (!TOPIC.equals(event.topic()) || !TAG.equals(event.tag())
                            || !MaterialApiClient.readTask(json.readTree(event.payload()), true).equals(event.task()))
                        throw new IllegalArgumentException("资料 Outbox 消息无效");
                    // 重新序列化白名单字段，永不把任意 Outbox 原文转发到 MQ。
                    var message = new Message(TOPIC, TAG, event.msgKey(), json.writeValueAsBytes(event.task()));
                    var result = producer.send(message, 10_000);
                    sent = result != null && result.getSendStatus() == SendStatus.SEND_OK;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false; // 未确认租约由 API 到期恢复，不占用关机线程继续网络请求。
                } catch (Exception e) { LOG.warn("资料消息投递失败，等待 Outbox 退避重投"); }
                try { api.ack(new MaterialApi.OutboxAck(event.eventId(), event.leaseToken(), sent)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                catch (Exception e) {
                    // Broker 已成功但 ACK 丢失时，仅等待租约恢复；不能反向记录发送失败。
                    LOG.warn("资料 Outbox 确认失败，等待租约恢复");
                    return false;
                }
                healthy &= sent;
            }
            return healthy;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { LOG.warn("资料 Outbox 领取失败，稍后重试"); return false; }
    }

    ConsumeConcurrentlyStatus consume(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        context.setDelayLevelWhenNextConsume(3); // Broker 延迟重投，不在消费线程内 sleep/retry。
        if (stopped || messages.size() != 1) return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        try {
            MessageExt message = messages.getFirst();
            if (!TOPIC.equals(message.getTopic()) || !TAG.equals(message.getTags())
                    || message.getBody() == null || message.getBody().length > 4096) throw new IllegalArgumentException();
            var event = MaterialApiClient.readTask(json.readTree(message.getBody()), true);
            String status = api.execute(event);
            if (!Set.of("ready", "failed", "processing", "ignored").contains(status)) throw new IllegalArgumentException();
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return ConsumeConcurrentlyStatus.RECONSUME_LATER; }
        catch (Exception e) {
            LOG.warn("资料执行未获有效确认，交由 MQ 延迟重投原任务");
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        running = false;
        consumer.suspend();
        if (relay != null) relay.shutdownNow();
        // SDK 等待在途消费最多 710s，大于单次内部请求 700s；API 客户端随后由容器关闭。
        try { consumer.shutdown(); }
        finally {
            producer.shutdown();
            if (relay != null) {
                try { if (!relay.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("资料 relay 线程尚未退出"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Override public boolean isRunning() { return running; }
}
