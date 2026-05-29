package com.lamastudio.backend.modules.auth.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class SessionDto {
    private UUID sessionId;
    private String deviceName;
    private String ipAddress;
    private Instant lastUsedAt;
    private boolean current;
}
