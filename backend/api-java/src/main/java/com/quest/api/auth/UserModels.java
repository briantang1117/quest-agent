package com.quest.api.auth;

import java.time.Instant;
import java.util.List;

public final class UserModels {
    private UserModels() {}
    public record Profile(String id, String username, String displayName, String role, String status,
                          boolean mustChangePassword, Instant createdAt, Instant lastLoginAt) {}
    public record TokenResult(String accessToken, Instant expiresAt, Profile user) {
        @Override public String toString() { return "TokenResult[redacted]"; }
    }
    public record UserPage(List<Profile> list, long total, int page, int size) {}
    public record SessionIdentity(Profile user, String sessionId) {}
    // 凭据对象禁用自动 toString，避免调试/异常日志包含 hash。
    static final class StoredUser {
        final Profile profile;
        final String hash;
        StoredUser(Profile profile, String hash) { this.profile = profile; this.hash = hash; }
    }
}
