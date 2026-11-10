package com.quest.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerApplicationTest {
    @Test void localWorkerStartsWithoutDatabaseOrWebServer() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(WorkerApplication.class).profiles("local")
                .properties("spring.main.banner-mode=off", "spring.main.keep-alive=false").run()) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.containsBean("dataSource")).isFalse();
            assertThat(context.getBeansOfType(MaterialMqWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(MaterialApiClient.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        }
    }
    @Test void materialSwitchFailsClosedWithoutInternalCredentials() {
        new ApplicationContextRunner().withUserConfiguration(MaterialMqConfiguration.class)
            .withPropertyValues("quest.material-mq.enabled=true", "quest.material-mq.name-server=127.0.0.1:1")
            .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage("资料 Worker 内部认证未配置"));
    }
    @Test void deploymentWorkerAlsoNeedsNoDatabaseWhenMaterialMqDisabled() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(WorkerApplication.class).profiles("deployment")
                .properties("spring.config.import=", "quest.material-mq.enabled=false", "spring.main.banner-mode=off", "spring.main.keep-alive=false").run()) {
            assertThat(context.containsBean("dataSource")).isFalse();
            assertThat(context.getBeansOfType(MaterialMqWorker.class)).isEmpty();
        }
    }
    @Test void enablingUnimplementedRelayFailsRatherThanSilentlyDroppingEvents() {
        new ApplicationContextRunner().withUserConfiguration(OutboxBoundary.class)
            .withPropertyValues("quest.outbox.enabled=true")
            .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage("Outbox relay is not implemented; keep quest.outbox.enabled=false"));
    }
}
