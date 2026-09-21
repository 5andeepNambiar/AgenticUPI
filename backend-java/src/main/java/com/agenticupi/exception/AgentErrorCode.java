package com.agenticupi.exception;

public enum AgentErrorCode {
    INVALID_SIGNATURE(401),
    REPLAY_WINDOW_EXCEEDED(401),
    MANDATE_REVOKED(403),
    MANDATE_EXPIRED(403),
    MANDATE_PAUSED(403),
    POLICY_VIOLATION(403),       // E.g., merchant category not allowed
    VELOCITY_EXCEEDED(429),      // Hit daily or per-txn limit
    INSUFFICIENT_BALANCE(400),
    IDEMPOTENCY_CONFLICT(409),   // Same idempotency_key, different request_hash
    DUPLICATE_REQUEST(409),      // Handled internally by returning cached response
    INTERNAL_ERROR(500);

    private final int httpStatus;

    AgentErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
