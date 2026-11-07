package com.quest.api;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.Date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"quest.security.jwt.secret=contract-test-secret-at-least-32-bytes-long"})
@AutoConfigureMockMvc @ActiveProfiles("local")
class ApiContractTest {
    @Autowired MockMvc mvc;
    static final String SECRET = "contract-test-secret-at-least-32-bytes-long";
    String token(String secret, Instant expiry, String issuer, String audience) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("contract-user").issuer(issuer).audience(audience)
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expiry)).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret)); return jwt.serialize();
    }
    String bearer() throws Exception { return "Bearer " + token(SECRET, Instant.now().plusSeconds(300), "quest-api", "quest-web"); }
    @Test void anonymousBusinessRequestIsDeniedWithEnvelope() throws Exception {
        mvc.perform(get("/api/v1/system/info")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40100)).andExpect(jsonPath("$.message").isString())
            .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void unlistedEndpointsAreNotAnonymous() throws Exception {
        mvc.perform(get("/api/v1/admin/questions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
    @Test void realActuatorHealthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
    }
    @Test void signedJwtCanAccessProtectedInfo() throws Exception {
        mvc.perform(get("/api/v1/system/info").header("Authorization", bearer()).header("X-Trace-Id", "contract-123"))
            .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "contract-123"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.service").value("api-java"))
            .andExpect(jsonPath("$.data.persistence").value("disabled"))
            .andExpect(jsonPath("$.traceId").value("contract-123"));
        assertThat(MDC.get("traceId")).isNull();
    }
    @Test void invalidTraceIsReplacedAndMatchesEnvelope() throws Exception {
        var result = mvc.perform(get("/api/v1/system/info").header("X-Trace-Id", "unsafe trace /"))
            .andExpect(status().isUnauthorized()).andReturn();
        String trace = result.getResponse().getHeader("X-Trace-Id");
        assertThat(trace).matches("[a-zA-Z0-9_-]{1,64}");
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + trace + "\"");
        assertThat(MDC.get("traceId")).isNull();
    }
    @Test void rejectsTamperedExpiredWrongIssuerAndAudienceTokens() throws Exception {
        for (String jwt : new String[]{"not-a-jwt", token("another-secret-at-least-32-bytes-long", Instant.now().plusSeconds(300), "quest-api", "quest-web"),
                token(SECRET, Instant.now().minusSeconds(120), "quest-api", "quest-web"),
                token(SECRET, Instant.now().plusSeconds(300), "wrong", "quest-web"),
                token(SECRET, Instant.now().plusSeconds(300), "quest-api", "wrong")}) {
            mvc.perform(get("/api/v1/system/info").header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        }
    }
    @Test void rejectsTokenWithoutExpiry() throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("user").issuer("quest-api").audience("quest-web").build());
        jwt.sign(new MACSigner(SECRET));
        mvc.perform(get("/api/v1/system/info").header("Authorization", "Bearer " + jwt.serialize())).andExpect(status().isUnauthorized());
    }
    @Test void forbiddenUsesUniformEnvelope() throws Exception {
        mvc.perform(get("/api/v1/test/admin").header("Authorization", bearer())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
    }
    @Test void validationAndMalformedJsonUseUniformEnvelope() throws Exception {
        for (String body : new String[]{"{\"value\":\"\"}", "{"}) {
            mvc.perform(post("/api/v1/test/validation").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000)).andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }
    @Test void unknownAuthenticatedEndpointIs404() throws Exception {
        mvc.perform(get("/api/v1/missing").header("Authorization", bearer())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
    }
    @Test void unhandledErrorsDoNotLeakInternals() throws Exception {
        mvc.perform(get("/api/v1/test/failure").header("Authorization", bearer()))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(50000))
            .andExpect(jsonPath("$.message").value("Internal server error"));
    }
    @Test void demoLoginDoesNotExist() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"admin\"}"))
            .andExpect(status().isUnauthorized());
    }
    @TestConfiguration static class TestEndpoints {
        @Bean TestController testController() { return new TestController(); }
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController static class TestController {
        record Input(@NotBlank String value) {}
        @GetMapping("/api/v1/test/admin") @PreAuthorize("hasRole('ADMIN')") public String admin() { return "secret"; }
        @PostMapping("/api/v1/test/validation") public String validate(@Valid @RequestBody Input input) { return input.value(); }
        @GetMapping("/api/v1/test/failure") public String failure() { throw new IllegalStateException("secret database password"); }
    }
}
