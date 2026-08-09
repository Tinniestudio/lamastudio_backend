package com.tinniestudio.api.modules.content.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;
    private final CategoryRepository categoryRepository;
    private final RabbitTemplate rabbitTemplate;

    @Transactional(readOnly = true)
    public Page<ContentSummaryResponse> list(
            ContentType type, String categorySlug,
            MaturityRating maturityRating, Boolean comingSoon,
            Pageable pageable) {

        Specification<Content> spec = ContentSpecifications.isPublished()
            .and(ContentSpecifications.hasType(type))
            .and(ContentSpecifications.hasCategory(categorySlug))
            .and(ContentSpecifications.hasMaturityRating(maturityRating))
            .and(ContentSpecifications.isComingSoon(comingSoon));

        return contentRepository.findAll(spec, pageable).map(ContentSummaryResponse::from);
    }

    @Cacheable(value = "content-detail", key = "#slug")
    @Transactional(readOnly = true)
    public ContentResponse getBySlug(String slug) {
        return contentRepository.findBySlug(slug)
            .filter(this::isPubliclyVisible)
            .map(ContentResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + slug));
    }

    @Cacheable(value = "content-detail", key = "#id")
    @Transactional(readOnly = true)
    public ContentResponse getById(UUID id) {
        return contentRepository.findById(id)
            .filter(this::isPubliclyVisible)
            .map(ContentResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
    }

    /**
     * Only PUBLISHED content is visible through the public, unauthenticated content endpoints.
     * DRAFT/REVIEW/PROCESSING/REJECTED/ARCHIVED content is only reachable via the admin/partner
     * endpoints, which are role-gated separately.
     */
    public boolean isPubliclyVisible(Content content) {
        return content.getStatus() == ContentStatus.PUBLISHED;
    }

    /**
     * Records a view event for a content item, published to the same analytics.ingest
     * queue consumed by AnalyticsConsumer/AnalyticsEventProcessor. Unlike playback manifest
     * generation (which requires auth for subscription/capability checks), this is a
     * lightweight, genuinely public "beacon" endpoint — {@code userId} is null for
     * unauthenticated callers, which is by design (Batch 16 #7: anonymous views are
     * tracked with userId=null for better analysis). Best-effort: publish failures never
     * fail the request.
     */
    @Transactional(readOnly = true)
    public void recordView(UUID id, UUID userId) {
        Content content = contentRepository.findById(id)
            .filter(this::isPubliclyVisible)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_ANALYTICS_INGEST, Map.of(
                "type", "VIEW_EVENT",
                "userId", userId != null ? userId.toString() : "",
                "contentId", content.getId().toString()
            ));
        } catch (Exception e) {
            log.warn("Analytics VIEW_EVENT publish failed (non-critical): {}", e.getMessage());
        }
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse create(CreateContentRequest req, UUID createdBy) {
        Content content = new Content();
        content.setTitle(req.title());
        content.setType(req.type());
        content.setStatus(ContentStatus.DRAFT);
        content.setMaturityRating(req.maturityRating() != null ? req.maturityRating() : MaturityRating.NOT_RATED);
        content.setDescription(req.description());
        content.setShortDescription(req.shortDescription());
        content.setReleaseDate(req.releaseDate());
        content.setComingSoon(req.comingSoon() != null ? req.comingSoon() : false);
        content.setDurationSeconds(req.durationSeconds());
        content.setCreatedBy(createdBy);
        content.setViewCount(0L);
        content.setFeatured(false);
        if (req.categoryIds() != null && !req.categoryIds().isEmpty()) {
            content.setCategories(new HashSet<>(categoryRepository.findAllById(req.categoryIds())));
        }
        try {
            return ContentResponse.from(contentRepository.save(content));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Content title conflict — slug already exists");
        }
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse update(UUID id, UpdateContentRequest req) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        if (req.title() != null)            content.setTitle(req.title());
        if (req.description() != null)      content.setDescription(req.description());
        if (req.shortDescription() != null) content.setShortDescription(req.shortDescription());
        if (req.maturityRating() != null)   content.setMaturityRating(req.maturityRating());
        if (req.releaseDate() != null)      content.setReleaseDate(req.releaseDate());
        if (req.language() != null)         content.setLanguage(req.language());
        if (req.country() != null)          content.setCountry(req.country());
        if (req.comingSoon() != null)       content.setComingSoon(req.comingSoon());
        if (req.durationSeconds() != null)  content.setDurationSeconds(req.durationSeconds());
        if (req.posterUrl() != null)        content.setPosterUrl(req.posterUrl());
        if (req.thumbnailUrl() != null)     content.setThumbnailUrl(req.thumbnailUrl());
        if (req.categoryIds() != null) {
            content.setCategories(new HashSet<>(categoryRepository.findAllById(req.categoryIds())));
        }
        return ContentResponse.from(contentRepository.save(content));
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public void delete(UUID id) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        contentRepository.delete(content);
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse transitionStatus(UUID id, ContentStatus targetStatus) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        validateTransition(content.getStatus(), targetStatus);
        content.setStatus(targetStatus);
        if (targetStatus == ContentStatus.PUBLISHED && content.getPublishedAt() == null) {
            content.setPublishedAt(Instant.now());
        }
        return ContentResponse.from(contentRepository.save(content));
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse toggleFeatured(UUID id) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        content.setFeatured(!content.getFeatured());
        return ContentResponse.from(contentRepository.save(content));
    }

    private void validateTransition(ContentStatus current, ContentStatus target) {
        boolean valid = switch (current) {
            case DRAFT      -> target == ContentStatus.REVIEW || target == ContentStatus.ARCHIVED;
            case REVIEW     -> target == ContentStatus.PROCESSING || target == ContentStatus.REJECTED || target == ContentStatus.ARCHIVED;
            case PROCESSING -> target == ContentStatus.PUBLISHED || target == ContentStatus.ARCHIVED;
            case REJECTED   -> target == ContentStatus.DRAFT || target == ContentStatus.ARCHIVED;
            case PUBLISHED  -> target == ContentStatus.ARCHIVED;
            case ARCHIVED   -> false;
        };
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid status transition: " + current + " → " + target);
        }
    }
}
