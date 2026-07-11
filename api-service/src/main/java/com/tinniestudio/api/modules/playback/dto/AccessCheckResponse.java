package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccessCheckResponse {
    private final boolean hasAccess;
    private final String reason;

    public static AccessCheckResponse granted() {
        return new AccessCheckResponse(true, null);
    }

    public static AccessCheckResponse denied(String reason) {
        return new AccessCheckResponse(false, reason);
    }
}
