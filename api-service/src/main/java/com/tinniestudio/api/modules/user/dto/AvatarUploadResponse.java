package com.tinniestudio.api.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class AvatarUploadResponse {

    private String uploadUrl;
    private String storageKey;
    private Instant expiresAt;
}
