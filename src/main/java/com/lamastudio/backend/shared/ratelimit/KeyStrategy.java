package com.lamastudio.backend.shared.ratelimit;

public enum KeyStrategy {
    USER_OR_IP,
    IP_ONLY,
    USER_ONLY,
    ENDPOINT_ONLY
}
