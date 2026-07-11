package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubtitleDto {
    private final String languageCode;
    private final String label;
    private final String url;
    private final boolean isDefault;
}
