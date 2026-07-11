package com.tinniestudio.api.modules.playback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
public class PlaybackManifestResponse {
    private final String manifestUrl;
    private final List<SubtitleDto> subtitles;
    private final Integer resumeAt;
    private final Integer duration;
}
