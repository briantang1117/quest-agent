package com.quest.api.auth;

public final class UserFailure extends RuntimeException {
    private final int status;
    private final int code;
    public UserFailure(int status, int code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public int code() { return code; }
    public static UserFailure invalid() { return new UserFailure(401, 40100, "Invalid credentials or session"); }
    public static UserFailure unavailable() { return new UserFailure(503, 50300, "User persistence is unavailable"); }
    public static UserFailure denied() { return new UserFailure(403, 40300, "Access denied"); }
}
