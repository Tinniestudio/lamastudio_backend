package com.tinniestudio.api.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(name = "ResolveDeviceLimitRequest", description = "Picks which device to sign out to complete a login that hit the device cap")
public class ResolveDeviceLimitRequest {

    @Schema(description = "Single-use token from the 409 DEVICE_LIMIT_REACHED response", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Resolution token is required")
    private String resolutionToken;

    @Schema(description = "sessionId of the device the user chose to sign out", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "A device to sign out must be selected")
    private String sessionIdToRevoke;
}
