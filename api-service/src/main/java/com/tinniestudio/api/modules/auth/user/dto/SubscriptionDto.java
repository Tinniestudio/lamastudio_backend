package com.tinniestudio.api.modules.auth.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class SubscriptionDto {
    private String plan;
    private String status;
    private int maxDevices;
    private int contentWatchesUsed;
    private Integer contentWatchesLimit;
    private boolean canWatch;
    private Instant expiresAt;
}
