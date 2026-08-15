package com.tinniestudio.api.modules.playback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
