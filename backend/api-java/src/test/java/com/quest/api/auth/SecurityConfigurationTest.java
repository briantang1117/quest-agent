package com.quest.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

class SecurityConfigurationTest {
    // Only the decoder factory is under test; no servlet security infrastructure is needed here.
    private final SecurityConfiguration configuration = new SecurityConfiguration();
    private org.springframework.mock.env.MockEnvironment env(String... profiles) {
        var environment = new org.springframework.mock.env.MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
    @Test void localWithoutSecretRejectsEveryToken() {
        JwtDecoder decoder = configuration.jwtDecoder("", "quest-api", "quest-web", env("local"));
        assertThatThrownBy(() -> decoder.decode("anything")).isInstanceOf(JwtException.class);
    }
    @Test void deploymentRequiresExplicitSecret() {
        assertThatThrownBy(() -> configuration.jwtDecoder("", "quest-api", "quest-web", env("deployment")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is required");
    }
    @Test void shortSecretFailsStartup() {
        assertThatThrownBy(() -> configuration.jwtDecoder("short", "quest-api", "quest-web", env("local")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32");
    }
    @Test void deploymentCannotBeCombinedWithLocalProfile() {
        assertThatThrownBy(() -> configuration.jwtDecoder("valid-test-secret-at-least-32-bytes-long", "quest-api", "quest-web", env("local", "deployment")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("must not be combined");
    }
}
