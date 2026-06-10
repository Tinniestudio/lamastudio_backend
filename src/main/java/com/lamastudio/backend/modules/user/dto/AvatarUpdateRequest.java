package com.lamastudio.backend.modules.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AvatarUpdateRequest {

    public enum Mode { UPLOAD, URL }

    @NotNull(message = "mode is required (UPLOAD or URL)")
    private Mode mode;

    private String mimeType;
    private Long fileSizeBytes;
    private String avatarUrl;
}
