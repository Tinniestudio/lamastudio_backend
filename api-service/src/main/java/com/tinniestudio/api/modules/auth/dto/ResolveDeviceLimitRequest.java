package com.tinniestudio.api.modules.auth.dto;

import lombok.Getter;
import lombok.Setter;

// Minimal placeholder — Task 6 adds validation annotations and Swagger metadata.
@Getter
@Setter
public class ResolveDeviceLimitRequest {

    private String resolutionToken;

    private String sessionIdToRevoke;
}
