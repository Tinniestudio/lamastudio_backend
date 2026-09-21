package com.tinniestudio.api.modules.search.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.search.dto.SearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final ContentRepository contentRepository;

    // Not @Cacheable: Page (concrete runtime type PageImpl, org.springframework.data.domain)
    // can't round-trip RedisConfig's cacheObjectMapper() — its BasicPolymorphicTypeValidator
    // only allows com.tinniestudio.* plus a short JDK allowlist, and PageImpl has no
    // default constructor/Jackson creator even if the allowlist were widened. Matches
    // ContentService.list(), the closest analog, which is also uncached for the same reason.
    @Override
    @Transactional(readOnly = true)
    public Page<ContentSummaryResponse> search(SearchRequest request) {
        String q = request.getQ() == null ? "" : request.getQ().trim();
        if (q.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Search query must be at least 2 characters");
        }

        String typeStr      = request.getType();
        String language     = request.getLanguage();
        String country      = request.getCountry();
        String categorySlug = request.getCategorySlug();
        String mainCategory = parseMainCategoryName(request.getMainCategory());
        var pageable        = PageRequest.of(request.getPage(), request.getLimit());

        Page<com.tinniestudio.api.shared.entity.Content> page = switch (request.getSort()) {
            case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, mainCategory, pageable);
            case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, mainCategory, pageable);
            default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, mainCategory, pageable);
        };

        return page.map(ContentSummaryResponse::from);
    }

    /** Converts the external slug to the enum's stored .name() (e.g. "tv-shows" -> "TV_SHOWS"); null/blank means no filter. */
    private String parseMainCategoryName(String mainCategorySlug) {
        if (mainCategorySlug == null || mainCategorySlug.isBlank()) return null;
        try {
            return com.tinniestudio.api.shared.entity.DomainEnums.MainCategory.fromSlug(mainCategorySlug).name();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
