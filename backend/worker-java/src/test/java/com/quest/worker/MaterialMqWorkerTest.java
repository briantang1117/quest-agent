package com.quest.worker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MaterialMqWorkerTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final FakeApi api = new FakeApi();
    private final FakeProducer producer = new FakeProducer();
    private final FakeConsumer consumer = new FakeConsumer();
    private final MaterialMqWorker worker = new MaterialMqWorker(api, producer, consumer, json);

    @Test void relayPublishesOnlyIdsAndAcknowledgesOnlyBrokerSendOk() throws Exception {
        api.events = List.of(event());
        assertThat(worker.relayOnce()).isTrue();
        assertThat(producer.sent).hasSize(1);
        var message = producer.sent.getFirst();
        assertThat(message.getTopic()).isEqualTo("quest-learning-materials");
        assertThat(message.getTags()).isEqualTo("generate");
        assertThat(json.readTree(message.getBody()).propertyNames()).containsExactlyInAnyOrder("taskId", "eventId", "generation");
        assertThat(api.acks).containsExactly(new MaterialApi.OutboxAck(api.events.getFirst().eventId(), "lease", true));
        api.acks.clear(); producer.status = SendStatus.FLUSH_DISK_TIMEOUT;
        assertThat(worker.relayOnce()).isFalse();
        assertThat(api.acks).containsExactly(new MaterialApi.OutboxAck(api.events.getFirst().eventId(), "lease", false));
        api.acks.clear(); producer.fail = true;
        assertThat(worker.relayOnce()).isFalse();
        assertThat(api.acks).allMatch(ack -> !ack.success());
    }

    @Test void ackNetworkFailureDoesNotTurnSuccessfulSendIntoFailureAck() throws Exception {
        api.events = List.of(event()); api.ackFails = true;
        assertThat(worker.relayOnce()).isFalse();
        assertThat(api.acks).hasSize(1).allMatch(MaterialApi.OutboxAck::success);
    }

    @Test void consumeAllValidStatesAndRedeliverNetworkFailureWithoutNewGeneration() throws Exception {
        var event = event(); var message = message(event.payload());
        for (String status : List.of("ready", "failed", "processing", "ignored")) {
            api.status = status;
            assertThat(worker.consume(List.of(message), context())).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        }
        assertThat(api.executed).hasSize(4).allMatch(task -> task.equals(event.task()));
        api.executeFails = true;
        var context = context();
        assertThat(worker.consume(List.of(message), context)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(context.getDelayLevelWhenNextConsume()).isPositive();
        assertThat(api.executed).hasSize(5).allMatch(task -> task.generation().equals(event.generation()));
    }

    @Test void rejectsExtraPrivateFieldsWrongTopicAndOversizedMessages() {
        var event = event();
        String privatePayload = event.payload().replace("}", ",\"messages\":[\"private\"]}");
        for (MessageExt message : List.of(message(privatePayload), message(" ".repeat(4097)), message("{}"))) {
            assertThat(worker.consume(List.of(message), context())).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        }
        var wrongTopic = message(event.payload()); wrongTopic.setTopic("another-topic");
        assertThat(worker.consume(List.of(wrongTopic), context())).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(api.executed).isEmpty();
    }

    @Test void lifecycleBoundsConcurrencyPreventsTopicFallbackAndStopsOwnClients() {
        worker.start();
        assertThat(worker.isRunning()).isTrue();
        assertThat(consumer.getConsumeThreadMin()).isEqualTo(2);
        assertThat(consumer.getConsumeThreadMax()).isEqualTo(2);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(1);
        assertThat(consumer.getConsumeTimeout()).isGreaterThanOrEqualTo(12);
        assertThat(producer.getCreateTopicKey()).isEqualTo("quest-learning-materials");
        assertThat(consumer.subscribed).isEqualTo("quest-learning-materials:generate");
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
        assertThat(consumer.stopped).isTrue(); assertThat(producer.stopped).isTrue();
        assertThat(worker.consume(List.of(message(event().payload())), context())).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        worker.stop();
    }

    @Test void relayFailureBacksOffWithoutSleepingAndStopCancelsScheduledWork() {
        var scheduler = new RecordingScheduler();
        var scheduledWorker = new MaterialMqWorker(api, producer, consumer, json, () -> scheduler);
        api.claimFails = true;
        scheduledWorker.start();
        for (int expected : List.of(2, 4, 8, 16, 32, 60, 60)) {
            scheduler.next.run();
            assertThat(scheduler.delaySeconds).isEqualTo(expected);
        }
        api.claimFails = false;
        scheduler.next.run();
        assertThat(scheduler.delaySeconds).isEqualTo(2);
        scheduledWorker.stop();
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test void schedulingStartupFailureAlsoResetsRunningAndClosesExecutor() {
        var scheduler = new RecordingScheduler();
        scheduler.shutdownNow();
        var broken = new MaterialMqWorker(api, producer, consumer, json, () -> scheduler);
        assertThatThrownBy(broken::start).isInstanceOf(IllegalStateException.class);
        assertThat(broken.isRunning()).isFalse();
        assertThat(producer.stopped).isTrue(); assertThat(consumer.stopped).isTrue();
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test void oversizedRelayBatchIsNotPublished() {
        api.events = java.util.Collections.nCopies(9, event());
        assertThat(worker.relayOnce()).isFalse();
        assertThat(producer.sent).isEmpty();
        assertThat(api.acks).isEmpty();
    }

    @Test void startupFailureClosesClientsAndDoesNotStartRelay() {
        consumer.failStart = true;
        assertThatThrownBy(worker::start).isInstanceOf(IllegalStateException.class);
        assertThat(worker.isRunning()).isFalse();
        assertThat(producer.stopped).isTrue(); assertThat(consumer.stopped).isTrue();
    }

    private MaterialApi.OutboxEvent event() {
        String task = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        String generation = UUID.randomUUID().toString();
        String payload = "{\"taskId\":\"" + task + "\",\"generation\":\"" + generation + "\",\"eventId\":\"" + id + "\"}";
        return new MaterialApi.OutboxEvent(id, "lease", task, generation, "quest-learning-materials", "generate", "key", payload);
    }
    private MessageExt message(String payload) {
        var message = new MessageExt(); message.setTopic("quest-learning-materials"); message.setTags("generate");
        message.setBody(payload.getBytes(StandardCharsets.UTF_8)); return message;
    }
    private ConsumeConcurrentlyContext context() { return new ConsumeConcurrentlyContext(new MessageQueue("quest-learning-materials", "fake", 0)); }

    private static class FakeApi implements MaterialApi {
        List<OutboxEvent> events = List.of();
        List<OutboxAck> acks = new ArrayList<>(); List<TaskEvent> executed = new ArrayList<>();
        String status = "ready"; boolean ackFails, executeFails, claimFails;
        public List<OutboxEvent> claim() { if (claimFails) throw new IllegalStateException("fake claim failure"); return events; }
        public void ack(OutboxAck ack) throws Exception { acks.add(ack); if (ackFails) throw new java.io.IOException(); }
        public String execute(TaskEvent task) throws Exception { executed.add(task); if (executeFails) throw new java.io.IOException(); return status; }
    }
    private static class RecordingScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor {
        Runnable next; long delaySeconds;
        RecordingScheduler() { super(1); }
        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, java.util.concurrent.TimeUnit unit) {
            next = command; delaySeconds = unit.toSeconds(delay);
            return super.schedule(() -> {}, 1, java.util.concurrent.TimeUnit.DAYS);
        }
    }
    private static class FakeProducer extends DefaultMQProducer {
        List<Message> sent = new ArrayList<>(); SendStatus status = SendStatus.SEND_OK; boolean fail, stopped;
        @Override public void start() {}
        @Override public List<MessageQueue> fetchPublishMessageQueues(String topic) { return List.of(new MessageQueue(topic, "fake", 0)); }
        @Override public SendResult send(Message message, long timeout) throws InterruptedException {
            if (fail) throw new IllegalStateException("fake broker unavailable");
            sent.add(message); var result = new SendResult(); result.setSendStatus(status); return result;
        }
        @Override public void shutdown() { stopped = true; }
    }
    private static class FakeConsumer extends DefaultMQPushConsumer {
        boolean stopped, failStart; String subscribed;
        @Override public void start() { if (failStart) throw new IllegalStateException("fake startup failure"); }
        @Override public void subscribe(String topic, String tag) { subscribed = topic + ":" + tag; }
        @Override public void registerMessageListener(MessageListenerConcurrently listener) {}
        @Override public void suspend() {}
        @Override public void shutdown() { stopped = true; }
    }
}
