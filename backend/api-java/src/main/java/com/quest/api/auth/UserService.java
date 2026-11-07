package com.quest.api.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static com.quest.api.auth.UserModels.*;

@Service
public class UserService {
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<PlatformTransactionManager> transactionProvider;
    private final String mode, secret, issuer, audience;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final String dummyHash = bcrypt.encode(UUID.randomUUID().toString());
    public UserService(ObjectProvider<JdbcTemplate> jdbcProvider, ObjectProvider<PlatformTransactionManager> transactionProvider,
                       @Value("${quest.persistence.mode:disabled}") String mode,
                       @Value("${quest.security.jwt.secret:}") String secret,
                       @Value("${quest.security.jwt.issuer:quest-api}") String issuer,
                       @Value("${quest.security.jwt.audience:quest-web}") String audience) {
        this.jdbcProvider = jdbcProvider; this.transactionProvider = transactionProvider; this.mode = mode;
        this.secret = secret; this.issuer = issuer; this.audience = audience;
    }
    private JdbcTemplate db() {
        JdbcTemplate db = jdbcProvider.getIfAvailable();
        if (!"postgresql".equals(mode) || db == null) throw UserFailure.unavailable();
        return db;
    }
    private <T> T transaction(Supplier<T> work) {
        db();
        var manager = transactionProvider.getIfAvailable();
        if (manager == null) throw UserFailure.unavailable();
        try { return new TransactionTemplate(manager).execute(status -> work.get()); }
        catch (DuplicateKeyException exception) { throw new UserFailure(409, 40900, "Username already exists"); }
        // 不拼接底层 SQL/参数到异常日志，故障绝不回退无状态认证。
        catch (DataAccessException exception) { throw UserFailure.unavailable(); }
    }
    static Profile profile(ResultSet rs) throws SQLException {
        OffsetDateTime last = rs.getObject("last_login_at", OffsetDateTime.class);
        return new Profile(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
            rs.getString("account_type"), rs.getString("status"), rs.getBoolean("must_change_password"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), last == null ? null : last.toInstant());
    }
    private StoredUser find(String clause, Object value, boolean lock) {
        var users = db().query("SELECT * FROM app_user WHERE " + clause + "=?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new StoredUser(profile(rs), rs.getString("password_hash")), value);
        return users.isEmpty() ? null : users.getFirst();
    }
    public TokenResult login(String username, String password, String trace) {
        return transaction(() -> {
            StoredUser user = find("username", username, true);
            boolean matches = matches(password, user == null ? dummyHash : user.hash);
            if (user == null || !matches || !"active".equals(user.profile.status())) throw UserFailure.invalid();
            db().update("UPDATE app_user SET last_login_at=?, updated_at=? WHERE id=?", now(), now(), Long.parseLong(user.profile.id()));
            return issue(find("id", Long.parseLong(user.profile.id()), false).profile);
        });
    }
    private boolean matches(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        if (hash == null || !hash.matches("^\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}$")) {
            bcrypt.matches(password, dummyHash); return false;
        }
        return bcrypt.matches(password, hash);
    }
    private TokenResult issue(Profile user) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw UserFailure.unavailable();
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS), expires = issued.plusSeconds(7200);
        UUID sid = UUID.randomUUID();
        db().update("INSERT INTO auth_session(id,user_id,created_at,expires_at) VALUES (?,?,?,?)", sid,
            Long.parseLong(user.id()), OffsetDateTime.ofInstant(issued, java.time.ZoneOffset.UTC), OffsetDateTime.ofInstant(expires, java.time.ZoneOffset.UTC));
        try {
            var claims = new JWTClaimsSet.Builder().subject(user.id()).issuer(issuer).audience(audience)
                .issueTime(Date.from(issued)).expirationTime(Date.from(expires)).claim("sid", sid.toString()).build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return new TokenResult(jwt.serialize(), expires, user);
        } catch (JOSEException ex) { throw UserFailure.unavailable(); }
    }
    public SessionIdentity session(String subject, String sid) {
        try { return requireSession(subject, sid, false); }
        catch (DataAccessException exception) { throw UserFailure.unavailable(); }
    }
    private SessionIdentity requireSession(String subject, String sid, boolean lock) {
        long id = numericId(subject);
        UUID session;
        try { session = UUID.fromString(sid); } catch (RuntimeException ex) { throw UserFailure.invalid(); }
        StoredUser user = find("id", id, lock);
        if (user == null || !"active".equals(user.profile.status()) || !Set.of("admin", "student").contains(user.profile.role())) throw UserFailure.invalid();
        Integer count = db().queryForObject("SELECT COUNT(*) FROM auth_session WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?", Integer.class, session, id, now());
        if (count == null || count != 1) throw UserFailure.invalid();
        return new SessionIdentity(user.profile, sid);
    }
    public void logout(SessionIdentity identity) {
        transaction(() -> {
            var live = requireSession(identity.user().id(), identity.sessionId(), true);
            db().update("UPDATE auth_session SET revoked_at=? WHERE id=?", now(), UUID.fromString(live.sessionId()));
            return null;
        });
    }
    public TokenResult changePassword(SessionIdentity identity, String current, String next, String trace) {
        password(next);
        return transaction(() -> {
            var live = requireSession(identity.user().id(), identity.sessionId(), true);
            var user = find("id", Long.parseLong(live.user().id()), false);
            if (!matches(current, user.hash)) throw UserFailure.invalid();
            if (matches(next, user.hash)) throw new IllegalArgumentException("New password must differ");
            db().update("UPDATE app_user SET password_hash=?, must_change_password=FALSE, updated_at=? WHERE id=?", bcrypt.encode(next), now(), Long.parseLong(user.profile.id()));
            revoke(user.profile.id()); audit(user.profile.id(), "user.change_password", user.profile.id(), trace);
            return issue(find("id", Long.parseLong(user.profile.id()), false).profile);
        });
    }
    private void revoke(String id) { db().update("UPDATE auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now(), Long.parseLong(id)); }
    private void admin(SessionIdentity identity) {
        var current = requireSession(identity.user().id(), identity.sessionId(), true);
        if (current.user().mustChangePassword()) throw new UserFailure(403, 40310, "Password change required");
        if (!"admin".equals(current.user().role())) throw UserFailure.denied();
    }
    public Profile create(SessionIdentity identity, String username, String displayName, String initialPassword, String trace) {
        username(username); password(initialPassword); String display = displayName(displayName);
        return transaction(() -> {
            admin(identity);
            db().update("INSERT INTO app_user(username,password_hash,display_name,account_type,must_change_password) VALUES (?,?,?,'student',TRUE)", username, bcrypt.encode(initialPassword), display);
            Profile created = find("username", username, false).profile;
            audit(identity.user().id(), "user.create", created.id(), trace); return created;
        });
    }
    public UserPage list(SessionIdentity identity, int page, int size, String keyword, String status) {
        if (page < 1 || size < 1 || size > 100 || keyword == null || keyword.length() > 128) throw new IllegalArgumentException("Invalid pagination");
        if (status != null && !status.isEmpty()) status(status);
        return transaction(() -> {
            admin(identity);
            String clause = " FROM app_user WHERE account_type='student'";
            List<Object> args = new ArrayList<>();
            if (status != null && !status.isEmpty()) { clause += " AND status=?"; args.add(status); }
            if (!keyword.isEmpty()) {
                clause += " AND (LOWER(username) LIKE ? ESCAPE '!' OR LOWER(display_name) LIKE ? ESCAPE '!')";
                String escaped = "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                args.add(escaped); args.add(escaped);
            }
            long total = db().queryForObject("SELECT COUNT(*)" + clause, Long.class, args.toArray());
            args.add(size); args.add((long) (page - 1) * size);
            List<Profile> users = db().query("SELECT *" + clause + " ORDER BY id LIMIT ? OFFSET ?", (rs, row) -> profile(rs), args.toArray());
            return new UserPage(users, total, page, size);
        });
    }
    public Profile setStatus(SessionIdentity identity, String id, String status, String trace) {
        long target = numericId(id); status(status);
        return transaction(() -> {
            admin(identity); student(target);
            db().update("UPDATE app_user SET status=?, updated_at=? WHERE id=?", status, now(), target);
            if ("disabled".equals(status)) revoke(id);
            audit(identity.user().id(), "user.status." + status, id, trace);
            return find("id", target, false).profile;
        });
    }
    public Profile resetPassword(SessionIdentity identity, String id, String next, String trace) {
        long target = numericId(id); password(next);
        return transaction(() -> {
            admin(identity); student(target);
            db().update("UPDATE app_user SET password_hash=?, must_change_password=TRUE, updated_at=? WHERE id=?", bcrypt.encode(next), now(), target);
            revoke(id); audit(identity.user().id(), "user.reset_password", id, trace);
            return find("id", target, false).profile;
        });
    }
    private void student(long id) {
        StoredUser user = find("id", id, true);
        if (user == null || !"student".equals(user.profile.role())) throw new UserFailure(404, 40400, "User not found");
    }
    private void audit(String actor, String action, String target, String trace) {
        db().update("INSERT INTO audit_log(actor,action,target_type,target_id,trace_id) VALUES (?,?,'user',?,?)", Long.parseLong(actor), action, target, trace);
    }
    static void username(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{2,31}")) throw new IllegalArgumentException("Invalid username");
    }
    static void password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 12 || value.codePointCount(0, value.length()) > 64 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Invalid password");
    }
    static String displayName(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid display name");
        String display = value.strip();
        if (display.isEmpty() || display.codePointCount(0, display.length()) > 64) throw new IllegalArgumentException("Invalid display name");
        return display;
    }
    private static void status(String status) {
        if (!Set.of("active", "disabled").contains(status == null ? "" : status)) throw new IllegalArgumentException("Invalid status");
    }
    static long numericId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid id");
        try { return Long.parseLong(id); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid id"); }
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(java.time.ZoneOffset.UTC); }
}
