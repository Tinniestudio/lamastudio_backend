package com.tinniestudio.api.modules.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateNotificationRequest {

    @NotNull(message = "notificationEmail is required")
    private Boolean notificationEmail;
}
