package com.tinniestudio.api.modules.discover.dto;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import java.util.List;

public record HomeSectionDto(
    String title,
    String sectionType,
    String categorySlug,
    List<ContentSummaryResponse> items
) {}
