package com.quest.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

public class AdminBootstrapTest {
    public static int advisoryLock(long key) { return 1; }
    @Test void bootstrapRequiresExplicitEnableAndConfirmation() {
        assertThatThrownBy(() -> AdminBootstrap.requireOptIn(Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminBootstrap.requireOptIn(Map.of("QUEST_ADMIN_BOOTSTRAP_ENABLED", "true"))).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> AdminBootstrap.requireOptIn(Map.of("QUEST_ADMIN_BOOTSTRAP_ENABLED", "true", "QUEST_ADMIN_BOOTSTRAP_CONFIRM", "CREATE_FIRST_ADMIN"))).doesNotThrowAnyException();
    }
    @Test void createsOnlyFirstAdminWithBcryptAndAuditsWithoutSecrets() {
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("DROP ALL OBJECTS");
        db.execute("CREATE ALIAS pg_advisory_xact_lock FOR 'com.quest.api.auth.AdminBootstrapTest.advisoryLock'");
        db.execute("CREATE TABLE app_user(id BIGSERIAL PRIMARY KEY,username VARCHAR(64) UNIQUE,password_hash VARCHAR(128),display_name VARCHAR(128),account_type VARCHAR(16),status VARCHAR(16) DEFAULT 'active',must_change_password BOOLEAN)");
        db.execute("CREATE TABLE audit_log(actor BIGINT,action VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(64),trace_id VARCHAR(64))");
        AdminBootstrap.initialize(db, "first_admin", "管理员", "Strong-admin-password-123");
        assertThat(db.queryForObject("SELECT account_type FROM app_user", String.class)).isEqualTo("admin");
        assertThat(new BCryptPasswordEncoder().matches("Strong-admin-password-123", db.queryForObject("SELECT password_hash FROM app_user", String.class))).isTrue();
        assertThat(db.queryForObject("SELECT must_change_password FROM app_user", Boolean.class)).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> AdminBootstrap.initialize(db, "another_admin", "管理员2", "Strong-admin-password-456")).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
    }
}
