package com.quest.api.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:users;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude=com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
    "quest.persistence.mode=postgresql", "quest.security.jwt.secret=user-module-test-secret-at-least-32-bytes",
    "quest.security.login.account-limit=50", "quest.security.login.source-limit=1000"
})
@ActiveProfiles("test") @AutoConfigureMockMvc
class UserModuleTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    static final String PASSWORD = "Initial-password-123";
    static final String NEXT = "Changed-password-456";
    final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach void schema() {
        db.execute("DROP ALL OBJECTS");
        db.execute("CREATE ROLE IF NOT EXISTS quest_app");
        db.execute("CREATE ROLE IF NOT EXISTS quest_ai");
        // H2 identity 不创建 PG 风格命名序列；显式占位以执行同一 V4 权限 SQL。
        db.execute("CREATE SEQUENCE app_user_id_seq");
        db.execute("CREATE SEQUENCE audit_log_id_seq");
        db.execute("CREATE TABLE app_user (id BIGSERIAL PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(128) NOT NULL, display_name VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'active', created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE audit_log (id BIGSERIAL PRIMARY KEY, actor BIGINT REFERENCES app_user(id), action VARCHAR(64) NOT NULL, target_type VARCHAR(64) NOT NULL, target_id VARCHAR(64), trace_id VARCHAR(64) NOT NULL, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        TestUserSchema.applyV4(db);
        seed("admin", "admin", false);
        seed("alice", "student", false);
        seed("first", "student", true);
    }
    void seed(String username, String role, boolean mustChange) {
        db.update("INSERT INTO app_user(username,password_hash,display_name,status,account_type,must_change_password) VALUES (?,?,?,'active',?,?)", username, bcrypt.encode(PASSWORD), username, role, mustChange);
    }
    JsonNode postJson(String path, String token, Map<String, ?> body, int expected) throws Exception {
        var req = post(path).contentType("application/json").content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    JsonNode login(String name) throws Exception { return postJson("/api/v1/auth/login", null, Map.of("username", name, "password", PASSWORD), 200).get("data"); }
    String token(String name) throws Exception { return login(name).get("accessToken").asText(); }
    String id(String name) { return db.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, name); }
    void me(String token, int expected) throws Exception { mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().is(expected)); }

    @Test void loginUsesBcryptAndPersistsTwoHourSessionWithStringIds() throws Exception {
        var data = login("alice");
        assertThat(data.get("user").get("id").isString()).isTrue();
        assertThat(data.get("user").get("role").asText()).isEqualTo("student");
        var jwt = SignedJWT.parse(data.get("accessToken").asText()).getJWTClaimsSet();
        assertThat(jwt.getExpirationTime().toInstant().getEpochSecond() - jwt.getIssueTime().toInstant().getEpochSecond()).isEqualTo(7200);
        assertThat(jwt.getStringClaim("sid")).isNotBlank();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, id("alice"))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT last_login_at FROM app_user WHERE username='alice'", java.time.OffsetDateTime.class)).isNotNull();
        me(data.get("accessToken").asText(), 200);
        assertThat(data.toString()).doesNotContain("password", "password_hash", "session");
    }
    @Test void badCredentialsAreGenericAndNoPlaintextFallback() throws Exception {
        var unknown = postJson("/api/v1/auth/login", null, Map.of("username", "unknown", "password", PASSWORD), 401);
        var wrong = postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", "wrong"), 401);
        assertThat(unknown.get("message")).isEqualTo(wrong.get("message"));
        db.update("UPDATE app_user SET password_hash=? WHERE username='alice'", PASSWORD);
        postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", PASSWORD), 401);
    }
    @Test void logoutRevokesOnlyCurrentSessionImmediately() throws Exception {
        String first = token("alice"), second = token("alice");
        postJson("/api/v1/auth/logout", first, Map.of(), 200);
        me(first, 401); me(second, 200);
    }
    @Test void passwordChangeRevokesAllOldSessionsAndIssuesReplacement() throws Exception {
        String first = token("alice"), second = token("alice");
        postJson("/api/v1/auth/change-password", first, Map.of("currentPassword", "bad", "newPassword", NEXT), 401);
        me(first, 200);
        var changed = postJson("/api/v1/auth/change-password", first, Map.of("currentPassword", PASSWORD, "newPassword", NEXT), 200).get("data");
        me(first, 401); me(second, 401); me(changed.get("accessToken").asText(), 200);
        assertThat(bcrypt.matches(NEXT, db.queryForObject("SELECT password_hash FROM app_user WHERE username='alice'", String.class))).isTrue();
    }
    @Test void forcedChangeAllowsOnlyMeLogoutAndChangePassword() throws Exception {
        String first = token("first"); me(first, 200);
        mvc.perform(get("/api/v1/system/info").header("Authorization", "Bearer " + first)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40310));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + first)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40310));
        var changed = postJson("/api/v1/auth/change-password", first, Map.of("currentPassword", PASSWORD, "newPassword", NEXT), 200).get("data");
        assertThat(changed.get("user").get("mustChangePassword").asBoolean()).isFalse();
        me(first, 401); me(changed.get("accessToken").asText(), 200);
    }
    @Test void adminCreatesStudentAndListsOnlyMetadataWithPagingAndFilters() throws Exception {
        String admin = token("admin");
        var user = postJson("/api/v1/admin/users", admin, Map.of("username", "new_user", "displayName", "  新用户  ", "initialPassword", PASSWORD), 200).get("data");
        assertThat(user.get("id").isString()).isTrue();
        assertThat(user.get("displayName").asText()).isEqualTo("新用户");
        assertThat(user.get("role").asText()).isEqualTo("student");
        assertThat(user.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(bcrypt.matches(PASSWORD, db.queryForObject("SELECT password_hash FROM app_user WHERE username='new_user'", String.class))).isTrue();
        mvc.perform(get("/api/v1/admin/users?keyword=新用户&size=1&page=1&status=active").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].username").value("new_user"))
            .andExpect(jsonPath("$.data.list[0].passwordHash").doesNotExist()).andExpect(jsonPath("$.data.list[0].questionCount").doesNotExist());
        mvc.perform(get("/api/v1/admin/users?size=1&page=2").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.list.length()").value(1));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='user.create'", Integer.class)).isEqualTo(1);
    }
    @Test void duplicateUsernameIsConflictAndTransactionsRollbackAuditFailure() throws Exception {
        String admin = token("admin");
        postJson("/api/v1/admin/users", admin, Map.of("username", "alice", "displayName", "Alice", "initialPassword", PASSWORD), 409);
        db.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_create CHECK (action <> 'user.create')");
        postJson("/api/v1/admin/users", admin, Map.of("username", "rollback", "displayName", "Rollback", "initialPassword", PASSWORD), 503);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username='rollback'", Integer.class)).isZero();
    }
    @Test void disableAndReenableDoNotResurrectOldTokens() throws Exception {
        String admin = token("admin"), alice = token("alice");
        for (String state : new String[]{"disabled", "active"}) {
            mvc.perform(patch("/api/v1/admin/users/" + id("alice") + "/status").header("Authorization", "Bearer " + admin).contentType("application/json").content("{\"status\":\"" + state + "\"}"))
                .andExpect(status().isOk());
            me(alice, 401);
            if (state.equals("disabled")) postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", PASSWORD), 401);
        }
    }
    @Test void resetRevokesSessionsAndForcesChange() throws Exception {
        String admin = token("admin"), alice = token("alice");
        var reset = postJson("/api/v1/admin/users/" + id("alice") + "/reset-password", admin, Map.of("newPassword", NEXT), 200);
        assertThat(reset.get("data").get("mustChangePassword").asBoolean()).isTrue(); me(alice, 401);
        postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", PASSWORD), 401);
        postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", NEXT), 200);
    }
    @Test void roleBoundariesIgnoreJwtRoleAndRejectAdminAsTarget() throws Exception {
        String admin = token("admin"), alice = token("alice");
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + alice)).andExpect(status().isForbidden());
        for (String path : new String[]{"/api/v1/questions", "/api/v1/practice", "/api/v1/agent/sessions", "/api/v1/admin/questions"})
            mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().isForbidden());
        postJson("/api/v1/admin/users/" + id("admin") + "/reset-password", admin, Map.of("newPassword", NEXT), 404);
        String sid = SignedJWT.parse(alice).getJWTClaimsSet().getStringClaim("sid");
        var claims = new JWTClaimsSet.Builder().subject(id("alice")).issuer("quest-api").audience("quest-web").claim("sid", sid).claim("role", "admin")
            .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims); jwt.sign(new MACSigner("user-module-test-secret-at-least-32-bytes"));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + jwt.serialize())).andExpect(status().isForbidden());
    }
    @Test void missingExpiredRevokedOrMismatchedSessionCannotAuthenticate() throws Exception {
        String alice = token("alice");
        db.update("UPDATE auth_session SET created_at=?, expires_at=?", java.time.OffsetDateTime.now().minusHours(3), java.time.OffsetDateTime.now().minusMinutes(1)); me(alice, 401);
        String second = token("alice"); db.update("UPDATE auth_session SET user_id=?", Long.parseLong(id("admin"))); me(second, 401);
        var claims = new JWTClaimsSet.Builder().subject(id("alice")).issuer("quest-api").audience("quest-web").expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims); jwt.sign(new MACSigner("user-module-test-secret-at-least-32-bytes"));
        me(jwt.serialize(), 401);
        mvc.perform(get("/api/v1/system/info").header("Authorization", "Bearer " + jwt.serialize())).andExpect(status().isUnauthorized());
    }
    @Test void validationRejectsInvalidNamesPasswordsPagingAndStatus() throws Exception {
        String admin = token("admin");
        for (String name : new String[]{"Abc", "12abc", "ab", "a-b", "a".repeat(33)})
            postJson("/api/v1/admin/users", admin, Map.of("username", name, "displayName", "Name", "initialPassword", PASSWORD), 400);
        for (String password : new String[]{"short", "a".repeat(65), "密".repeat(25)})
            postJson("/api/v1/admin/users", admin, Map.of("username", "valid", "displayName", "Name", "initialPassword", password), 400);
        for (String query : new String[]{"size=101", "page=0", "status=unknown", "page=x", "keyword=" + "a".repeat(129)})
            mvc.perform(get("/api/v1/admin/users?" + query).header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/users/not-an-id/status").header("Authorization", "Bearer " + admin).contentType("application/json").content("{\"status\":\"active\"}")).andExpect(status().isBadRequest());
    }
    @Test void passwordWhitespaceIsNotTrimmedAndSamePasswordRejected() throws Exception {
        String alice = token("alice");
        postJson("/api/v1/auth/change-password", alice, Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD), 400);
        String padded = "  different-password  ";
        postJson("/api/v1/auth/change-password", alice, Map.of("currentPassword", PASSWORD, "newPassword", padded), 200);
        postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", padded.trim()), 401);
        postJson("/api/v1/auth/login", null, Map.of("username", "alice", "password", padded), 200);
    }
    @Test void concurrentChangesCannotReuseRevokedSession() throws Exception {
        String alice = token("alice");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> change = () -> mvc.perform(post("/api/v1/auth/change-password").header("Authorization", "Bearer " + alice)
                .contentType("application/json").content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", NEXT))))
                .andReturn().getResponse().getStatus();
            var results = executor.invokeAll(java.util.List.of(change, change));
            assertThat(java.util.List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 401);
        }
        me(alice, 401);
    }
    @Test void concurrentDisableAndLoginLeaveNoValidToken() throws Exception {
        String admin = token("admin");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var login = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username", "alice", "password", PASSWORD))))
                    .andReturn().getResponse();
            });
            var disable = executor.submit(() -> {
                start.await();
                return mvc.perform(patch("/api/v1/admin/users/" + id("alice") + "/status").header("Authorization", "Bearer " + admin)
                    .contentType("application/json").content("{\"status\":\"disabled\"}")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(disable.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            var response = login.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.getStatus()).isIn(200, 401);
            if (response.getStatus() == 200) me(json.readTree(response.getContentAsString()).get("data").get("accessToken").asText(), 401);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, id("alice"))).isZero();
    }
    @Test void databaseSessionQueryFailureIsClosedWithoutSqlLeak() throws Exception {
        String alice = token("alice");
        db.execute("DROP TABLE auth_session");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + alice))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))));
    }
    @Test void noPublicBootstrapOrRegistrationExists() throws Exception {
        for (String path : new String[]{"/api/v1/auth/register", "/api/v1/auth/bootstrap", "/api/v1/admin/bootstrap"})
            mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
}
