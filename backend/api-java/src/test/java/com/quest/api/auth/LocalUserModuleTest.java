package com.quest.api.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Date;
import java.time.Instant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "quest.security.jwt.secret=local-test-secret-at-least-32-bytes-long", "quest.security.login.account-limit=1"})
@ActiveProfiles("local") @AutoConfigureMockMvc
class LocalUserModuleTest {
    @Autowired MockMvc mvc;
    @Test void databaseUnavailableDoesNotCreateFakeUsersOrSwallowServerFailure() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"alice\",\"password\":\"A-password-123\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"alice\",\"password\":\"A-password-123\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(42900)).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void evenSignedSessionCannotFallbackWhenDatabaseAbsent() throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("1").claim("sid", java.util.UUID.randomUUID().toString()).issuer("quest-api").audience("quest-web")
            .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims); jwt.sign(new MACSigner("local-test-secret-at-least-32-bytes-long"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + jwt.serialize()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
    }
    @Test void anonymousBusinessStillRequiresAuthenticationAndHealthIsPublic() throws Exception {
        mvc.perform(get("/api/v1/system/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
