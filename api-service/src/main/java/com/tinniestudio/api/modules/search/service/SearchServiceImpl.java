package com.tinniestudio.api.modules.search.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.search.dto.SearchRequest;
import com.tinniestudio.api.modules.search.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final ContentRepository contentRepository;

    @Override
    @Cacheable(value = "search", key = "(#request.q != null ? #request.q.trim().toLowerCase() : '') + '::' "
        + "+ (#request.type != null ? #request.type.name() : '') + '::' "
        + "+ (#request.categorySlug != null ? #request.categorySlug : '') + '::' "
        + "+ (#request.language != null ? #request.language : '') + '::' "
        + "+ (#request.country != null ? #request.country : '') + '::' "
        + "+ #request.sort.name() + '::' "
        + "+ #request.page + '::' + #request.limit")
    @Transactional(readOnly = true)
    public SearchResponse search(SearchRequest request) {
        String q = request.getQ() == null ? "" : request.getQ().trim();
        if (q.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Search query must be at least 2 characters");
        }

        String typeStr      = request.getType() != null ? request.getType().name() : null;
        String language     = request.getLanguage();
        String country      = request.getCountry();
        String categorySlug = request.getCategorySlug();
        var pageable        = PageRequest.of(request.getPage(), request.getLimit());

        Page<com.tinniestudio.api.shared.entity.Content> page = switch (request.getSort()) {
            case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, pageable);
            case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, pageable);
            default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, pageable);
        };

        List<ContentSummaryResponse> results = page.map(ContentSummaryResponse::from).toList();

        int totalPages = page.getTotalElements() == 0 ? 0 : page.getTotalPages();

        return new SearchResponse(
            results,
            page.getTotalElements(),
            request.getPage(),
            request.getLimit(),
            totalPages
        );
    }
}
