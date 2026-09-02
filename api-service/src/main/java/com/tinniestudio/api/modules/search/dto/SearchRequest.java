package com.tinniestudio.api.modules.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SearchRequest {

    @NotBlank(message = "Search query must not be blank")
    @Size(min = 2, max = 200, message = "Query must be between 2 and 200 characters")
    private String q;

    private String type;                // content-type slug, null = all types
    private String categorySlug;        // null = all categories
    private String language;            // null = all languages
    private String country;             // null = all countries

    private SearchSort sort = SearchSort.RELEVANT;

    @Min(0) private int page = 0;
    @Min(1) @Max(50) private int limit = 20;

    public enum SearchSort { RELEVANT, LATEST, POPULAR }
}
