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
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        }
    }
    @Test void enablingUnimplementedRelayFailsRatherThanSilentlyDroppingEvents() {
        new ApplicationContextRunner().withUserConfiguration(OutboxBoundary.class)
            .withPropertyValues("quest.outbox.enabled=true")
            .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage("Outbox relay is not implemented; keep quest.outbox.enabled=false"));
    }
}
