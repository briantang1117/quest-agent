package com.quest.worker;

import java.util.List;
import java.util.UUID;

/** Worker 仅传递任务标识；业务状态和幂等认领由 API 维护。 */
interface MaterialApi {
    List<OutboxEvent> claim() throws Exception;
    void ack(OutboxAck ack) throws Exception;
    String execute(TaskEvent event) throws Exception;

    record TaskEvent(String taskId, String generation, String eventId) {
        public TaskEvent {
            UUID.fromString(taskId);
            UUID.fromString(eventId);
            UUID.fromString(generation);
        }
    }
    record OutboxEvent(String eventId, String leaseToken, String taskId, String generation,
                       String topic, String tag, String msgKey, String payload) {
        TaskEvent task() { return new TaskEvent(taskId, generation, eventId); }
    }
    record OutboxAck(String eventId, String leaseToken, boolean success) {}
}
