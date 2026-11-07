package com.quest.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 单进程固定窗口限制；不信任 X-Forwarded-For。多副本前必须换共享限流。 */
@Component
public final class LoginLimiter {
    private record Window(long started, int count) {}
    private final Map<String, Window> accounts = new HashMap<>(), sources = new HashMap<>();
    private final int accountLimit, sourceLimit, capacity;
    private final long windowMillis;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public LoginLimiter(@Value("${quest.security.login.account-limit:10}") int accountLimit,
                        @Value("${quest.security.login.source-limit:60}") int sourceLimit) {
        this(accountLimit, sourceLimit, 10000, Duration.ofMinutes(15), Clock.systemUTC());
    }
    LoginLimiter(int accountLimit, int sourceLimit, int capacity, Duration window, Clock clock) {
        if (accountLimit < 1 || sourceLimit < 1 || capacity < 1 || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("Invalid login limiter settings");
        this.accountLimit = accountLimit; this.sourceLimit = sourceLimit; this.capacity = capacity;
        this.windowMillis = window.toMillis(); this.clock = clock;
    }
    public synchronized void acquire(String account, String source) {
        long now = clock.millis();
        accounts.values().removeIf(w -> now - w.started >= windowMillis);
        sources.values().removeIf(w -> now - w.started >= windowMillis);
        // 已被限制的来源不得继续创建新账号窗口，避免单来源耗尽账号 map。
        if (!take(sources, source, sourceLimit, now)) throw new UserFailure(429, 42900, "Too many login attempts");
        if (!take(accounts, account, accountLimit, now)) throw new UserFailure(429, 42900, "Too many login attempts");
    }
    private boolean take(Map<String, Window> map, String key, int limit, long now) {
        Window previous = map.get(key);
        if (previous == null) {
            if (map.size() >= capacity) return false;
            map.put(key, new Window(now, 1)); return true;
        }
        if (previous.count >= limit) return false;
        map.put(key, new Window(previous.started, previous.count + 1)); return true;
    }
}
