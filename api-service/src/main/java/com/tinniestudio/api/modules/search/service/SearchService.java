package com.tinniestudio.api.modules.search.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.search.dto.SearchRequest;
import org.springframework.data.domain.Page;

public interface SearchService {
    Page<ContentSummaryResponse> search(SearchRequest request);
}
