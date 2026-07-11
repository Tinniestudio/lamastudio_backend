package com.tinniestudio.api.modules.playback.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.UUID;

@Data
public class ProgressRequest {
    private UUID contentId;
    private UUID episodeId;

    @NotNull @Min(0)
    private Integer progressSeconds;

    @NotNull @Min(1)
    private Integer durationSeconds;

    private String deviceType;
}
