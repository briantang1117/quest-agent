package com.quest.api.auth;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;

public final class TestUserSchema {
    private TestUserSchema() {}
    public static void applyV4(JdbcTemplate db) {
        try {
            String sql = new ClassPathResource("db/migration/V4__user_accounts_and_sessions.sql").getContentAsString(StandardCharsets.UTF_8);
            // H2 不支持 PG sequence GRANT；只剔除此项，表 DDL/数据变更及其他权限语句真实执行。
            String sequenceGrant = "GRANT USAGE, SELECT ON SEQUENCE app_user_id_seq, audit_log_id_seq TO quest_app;";
            if (!sql.contains(sequenceGrant)) throw new AssertionError("Expected explicit sequence privileges");
            sql = sql.replace(sequenceGrant, "");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(db.getDataSource());
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
}
