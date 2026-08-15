package com.tinniestudio.api.modules.episode.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record ReorderEpisodesRequest(
    @NotEmpty List<UUID> episodeIds
) {}
