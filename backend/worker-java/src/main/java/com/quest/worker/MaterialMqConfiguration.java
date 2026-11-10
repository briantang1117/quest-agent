package com.quest.worker;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 关闭时不构造内部 HTTP/MQ 客户端，不要求任何凭据或网络资源。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quest.material-mq.enabled", havingValue = "true")
class MaterialMqConfiguration {
    @Bean(destroyMethod = "close")
    MaterialApiClient materialApiClient(
            @Value("${quest.material-mq.api-service-url:http://127.0.0.1:8080}") String base,
            @Value("${quest.material-mq.service-token:}") String token,
            @Value("${quest.material-mq.hmac-secret:}") String secret) {
        return new MaterialApiClient(base, token, secret, JsonMapper.builder().build());
    }

    @Bean
    MaterialMqWorker materialMqWorker(MaterialApiClient api,
            @Value("${quest.material-mq.name-server:}") String nameServer) {
        if (nameServer.isBlank()) throw new IllegalArgumentException("资料 Worker NameServer 未配置");
        var producer = new DefaultMQProducer(MaterialMqWorker.PRODUCER_GROUP);
        var consumer = new DefaultMQPushConsumer(MaterialMqWorker.CONSUMER_GROUP);
        producer.setNamesrvAddr(nameServer);
        consumer.setNamesrvAddr(nameServer);
        return new MaterialMqWorker(api, producer, consumer, JsonMapper.builder().build());
    }
}
