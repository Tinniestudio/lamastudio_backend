package com.tinniestudio.api.modules.discover.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DiscoverService {

    private final ContentRepository contentRepository;
    private final HomepageSectionRepository sectionRepository;

    @Cacheable(value = "discover", key = "'trending-' + #limit")
    @Transactional(readOnly = true)
    public List<ContentSummaryResponse> trending(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished(),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "viewCount"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'featured-' + #limit")
    @Transactional(readOnly = true)
    public List<ContentSummaryResponse> featured(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished().and(ContentSpecifications.isFeatured(true)),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'new-releases-' + #limit")
    @Transactional(readOnly = true)
    public List<ContentSummaryResponse> newReleases(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished().and(ContentSpecifications.isComingSoon(false)),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'coming-soon-' + #limit")
    @Transactional(readOnly = true)
    public List<ContentSummaryResponse> comingSoon(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isComingSoon(true).and(ContentSpecifications.isViewable()),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "releaseDate"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'by-category-' + #categorySlug + '-' + #limit")
    @Transactional(readOnly = true)
    public List<ContentSummaryResponse> byCategory(String categorySlug, int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished().and(ContentSpecifications.hasCategory(categorySlug)),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'home'")
    @Transactional(readOnly = true)
    public List<HomeSectionDto> home() {
        return sectionRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
            .stream()
            .map(section -> {
                List<ContentSummaryResponse> items = resolveSection(
                    section.getSectionType(),
                    section.getCategory() != null ? section.getCategory().getSlug() : null,
                    20
                );
                return new HomeSectionDto(
                    section.getTitle(),
                    section.getSectionType().name(),
                    section.getCategory() != null ? section.getCategory().getSlug() : null,
                    items
                );
            })
            .toList();
    }

    private List<ContentSummaryResponse> resolveSection(SectionType type, String categorySlug, int limit) {
        return switch (type) {
            case TRENDING          -> trending(limit);
            case FEATURED          -> featured(limit);
            case NEW_RELEASES      -> newReleases(limit);
            case COMING_SOON       -> comingSoon(limit);
            case CONTINUE_WATCHING -> List.of(); // populated in Batch 8
            case CATEGORY          -> categorySlug != null ? byCategory(categorySlug, limit) : List.of();
        };
    }
}
