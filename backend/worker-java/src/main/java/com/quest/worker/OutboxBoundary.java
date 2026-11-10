package com.quest.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** 保留旧通用 Outbox 的拒绝开关；资料 MQ 使用独立 MaterialMqWorker。 */
@Configuration(proxyBeanMethods = false)
public class OutboxBoundary {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxBoundary.class);
    public OutboxBoundary(@Value("${quest.outbox.enabled:false}") boolean enabled) {
        if (enabled) throw new IllegalStateException("Outbox relay is not implemented; keep quest.outbox.enabled=false");
        LOG.info("旧通用 Outbox 已关闭；资料任务仅由 quest.material-mq.enabled 独立控制");
    }
}
