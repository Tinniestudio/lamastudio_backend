package com.lamastudio.backend.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AvatarConfirmRequest {

    @NotBlank(message = "storageKey is required")
    private String storageKey;
}
