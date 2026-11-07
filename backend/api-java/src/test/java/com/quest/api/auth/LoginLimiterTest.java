package com.quest.api.auth;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class LoginLimiterTest {
    @Test void limitsByAccountAndSourceIndependently() {
        var account = new LoginLimiter(2, 100, 100, Duration.ofMinutes(15), Clock.systemUTC());
        account.acquire("alice", "ip1"); account.acquire("alice", "ip2");
        assertThatThrownBy(() -> account.acquire("alice", "ip3")).isInstanceOfSatisfying(UserFailure.class, ex -> assertThat(ex.code()).isEqualTo(42900));
        var source = new LoginLimiter(100, 2, 100, Duration.ofMinutes(15), Clock.systemUTC());
        source.acquire("alice", "ip"); source.acquire("bob", "ip");
        assertThatThrownBy(() -> source.acquire("carol", "ip")).isInstanceOf(UserFailure.class);
    }
    @Test void blockedSourceCannotExhaustAccountCapacity() {
        var limiter = new LoginLimiter(10, 1, 2, Duration.ofMinutes(15), Clock.systemUTC());
        limiter.acquire("alice", "blocked");
        assertThatThrownBy(() -> limiter.acquire("unseen", "blocked")).isInstanceOf(UserFailure.class);
        assertThatCode(() -> limiter.acquire("bob", "fresh")).doesNotThrowAnyException();
    }
    @Test void boundsMemoryAndExpiresWindow() {
        class MutableClock extends Clock {
            Instant instant = Instant.EPOCH;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return instant; }
        }
        var clock = new MutableClock();
        var limiter = new LoginLimiter(10, 10, 1, Duration.ofMinutes(15), clock);
        limiter.acquire("alice", "ip");
        assertThatThrownBy(() -> limiter.acquire("bob", "ip2")).isInstanceOf(UserFailure.class);
        clock.instant = clock.instant.plusSeconds(900);
        assertThatCode(() -> limiter.acquire("bob", "ip2")).doesNotThrowAnyException();
    }
}
