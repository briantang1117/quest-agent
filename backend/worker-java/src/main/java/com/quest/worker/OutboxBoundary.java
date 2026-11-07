package com.quest.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** No poller is registered. Ordering, publisher acknowledgments, leases and idempotency remain a future unit. */
@Configuration(proxyBeanMethods = false)
public class OutboxBoundary {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxBoundary.class);
    public OutboxBoundary(@Value("${quest.outbox.enabled:false}") boolean enabled) {
        if (enabled) throw new IllegalStateException("Outbox relay is not implemented; keep quest.outbox.enabled=false");
        LOG.warn("Outbox relay DISABLED: no events are being published; this worker is an architecture shell only");
    }
}
