package com.quest.api.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.Console;
import java.util.Arrays;
import java.util.Map;

/** 独立非 Spring/非 Web 命令；不读取应用 .env，不在 API 启动时执行。 */
public final class AdminBootstrap {
    private AdminBootstrap() {}
    public static void main(String[] args) {
        try {
            Map<String, String> env = System.getenv();
            requireOptIn(env);
            if (args.length != 0) throw new IllegalStateException("Command line credentials are forbidden");
            Console console = System.console();
            if (console == null) throw new IllegalStateException("Interactive terminal required");
            String url = required(env, "QUEST_BOOTSTRAP_JDBC_URL");
            String dbUser = required(env, "QUEST_BOOTSTRAP_DB_USER");
            if (!url.startsWith("jdbc:postgresql:") || !dbUser.equals("quest_owner")) throw new IllegalStateException("Dedicated PostgreSQL owner credentials required");
            char[] dbPassword = console.readPassword("数据库迁移账号口令（不回显）: ");
            char[] password = null, repeated = null;
            try {
                String username = console.readLine("首个 Admin 用户名: ");
                String display = console.readLine("展示名: ");
                password = console.readPassword("Admin 口令（不回显，12–64字符且UTF8<=72字节）: ");
                repeated = console.readPassword("再次输入 Admin 口令: ");
                if (password == null || !Arrays.equals(password, repeated)) throw new IllegalStateException("Password confirmation mismatch");
                var ds = new DriverManagerDataSource(url, dbUser, new String(dbPassword));
                initialize(new JdbcTemplate(ds), username, display, new String(password));
                console.printf("首个 Admin 已创建；未输出凭据。请退出此一次性环境。%n");
            } finally {
                if (dbPassword != null) Arrays.fill(dbPassword, '\0');
                if (password != null) Arrays.fill(password, '\0');
                if (repeated != null) Arrays.fill(repeated, '\0');
            }
        } catch (Exception exception) {
            // 不打印 JDBC URL、SQL 参数、环境或异常堆栈。
            System.err.println("Admin 初始化被拒绝或失败；请核验显式授权、独立连接配置、V4及账号规则。未输出凭据。");
            System.exit(1);
        }
    }
    static void requireOptIn(Map<String, String> env) {
        if (!"true".equals(env.get("QUEST_ADMIN_BOOTSTRAP_ENABLED")) || !"CREATE_FIRST_ADMIN".equals(env.get("QUEST_ADMIN_BOOTSTRAP_CONFIRM")))
            throw new IllegalStateException("Bootstrap disabled");
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing bootstrap configuration");
        return value;
    }
    static void initialize(JdbcTemplate db, String username, String displayName, String password) {
        UserService.username(username); UserService.password(password); String display = UserService.displayName(displayName);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        transaction.executeWithoutResult(status -> {
            // 跨进程串行化首次管理员检查/写入，锁仅随事务持有。
            db.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, 73951820261107L);
            Long existing = db.queryForObject("SELECT COUNT(*) FROM app_user WHERE account_type='admin'", Long.class);
            if (existing == null || existing != 0) throw new IllegalStateException("An admin already exists");
            db.update("INSERT INTO app_user(username,password_hash,display_name,account_type,status,must_change_password) VALUES (?,?,?,'admin','active',FALSE)",
                username, new BCryptPasswordEncoder(12).encode(password), display);
            Long id = db.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
            db.update("INSERT INTO audit_log(actor,action,target_type,target_id,trace_id) VALUES (?,'admin.bootstrap','user',?,?)", id, String.valueOf(id), java.util.UUID.randomUUID().toString());
        });
    }
}
