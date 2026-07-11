package com.tinniestudio.api.modules.playback.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubtitleDto {
    private final String languageCode;
    private final String label;
    private final String url;
    @JsonProperty("isDefault")
    private final boolean isDefault;
}
