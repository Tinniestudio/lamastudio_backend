package com.tinniestudio.api.modules.search.service;

import com.tinniestudio.api.modules.search.dto.SearchRequest;
import com.tinniestudio.api.modules.search.dto.SearchResponse;

public interface SearchService {
    SearchResponse search(SearchRequest request);
}
