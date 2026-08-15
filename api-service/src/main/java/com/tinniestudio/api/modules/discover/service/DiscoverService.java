package com.tinniestudio.api.modules.discover.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.modules.playback.repository.WatchProgressRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscoverService {

    private final ContentRepository contentRepository;
    private final HomepageSectionRepository sectionRepository;
    private final WatchProgressRepository watchProgressRepository;

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
            ContentSpecifications.isComingSoon(true).and(ContentSpecifications.isPublished()),
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

    @Cacheable(value = "recommendations", key = "#userId")
    @Transactional(readOnly = true)
    public List<ContentSummaryResponse> recommended(UUID userId) {
        // 1. Fetch last 30 watched content IDs
        List<UUID> watchedIds = watchProgressRepository
            .findRecentlyWatchedContentIds(userId, PageRequest.of(0, 30));

        List<ContentSummaryResponse> candidates = new ArrayList<>();

        if (!watchedIds.isEmpty()) {
            // 2. Load watched content → extract category IDs
            List<com.tinniestudio.api.shared.entity.Content> watchedContent =
                contentRepository.findAllById(watchedIds);

            Set<UUID> categoryIds = watchedContent.stream()
                .flatMap(c -> c.getCategories().stream())
                .map(Category::getId)
                .collect(Collectors.toSet());

            if (!categoryIds.isEmpty()) {
                // 3. Find published content in those categories, not already watched
                var spec = ContentSpecifications.isPublished()
                    .and(ContentSpecifications.hasAnyCategory(categoryIds))
                    .and(ContentSpecifications.notInIds(watchedIds));

                candidates = contentRepository
                    .findAll(spec, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "viewCount")))
                    .map(ContentSummaryResponse::from)
                    .toList();
            }
        }

        // 4. Supplement with trending if fewer than 20 results
        if (candidates.size() < 20) {
            int needed = 20 - candidates.size();
            Set<UUID> alreadyIn = candidates.stream()
                .map(ContentSummaryResponse::id)
                .collect(Collectors.toSet());
            alreadyIn.addAll(watchedIds);

            List<ContentSummaryResponse> trendingItems = contentRepository
                .findAll(
                    ContentSpecifications.isPublished().and(ContentSpecifications.notInIds(alreadyIn)),
                    PageRequest.of(0, needed, Sort.by(Sort.Direction.DESC, "viewCount"))
                )
                .map(ContentSummaryResponse::from)
                .toList();

            candidates = new ArrayList<>(candidates);
            candidates.addAll(trendingItems);
        }

        // 5. Deduplicate preserving order, cap at 20
        Map<UUID, ContentSummaryResponse> seen = new LinkedHashMap<>();
        for (ContentSummaryResponse item : candidates) {
            seen.putIfAbsent(item.id(), item);
            if (seen.size() == 20) break;
        }
        return new ArrayList<>(seen.values());
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
