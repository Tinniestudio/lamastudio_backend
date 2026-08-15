package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public class ContentSpecifications {

    private ContentSpecifications() {}

    public static Specification<Content> hasStatus(ContentStatus status) {
        return (root, query, cb) -> status == null ? cb.conjunction()
            : cb.equal(root.get("status"), status);
    }

    public static Specification<Content> hasCreatedBy(UUID createdBy) {
        return (root, query, cb) -> createdBy == null ? cb.conjunction()
            : cb.equal(root.get("createdBy"), createdBy);
    }

    public static Specification<Content> titleContains(String q) {
        return (root, query, cb) -> (q == null || q.isBlank()) ? cb.conjunction()
            : cb.like(cb.lower(root.get("title")), "%" + q.toLowerCase() + "%");
    }

    public static Specification<Content> hasType(ContentType type) {
        return (root, query, cb) -> type == null ? cb.conjunction()
            : cb.equal(root.get("type"), type);
    }

    public static Specification<Content> hasMaturityRating(MaturityRating rating) {
        return (root, query, cb) -> rating == null ? cb.conjunction()
            : cb.equal(root.get("maturityRating"), rating);
    }

    public static Specification<Content> isComingSoon(Boolean comingSoon) {
        return (root, query, cb) -> comingSoon == null ? cb.conjunction()
            : cb.equal(root.get("comingSoon"), comingSoon);
    }

    public static Specification<Content> isFeatured(Boolean featured) {
        return (root, query, cb) -> featured == null ? cb.conjunction()
            : cb.equal(root.get("featured"), featured);
    }

    public static Specification<Content> hasCategory(String categorySlug) {
        return (root, query, cb) -> {
            if (categorySlug == null) return cb.conjunction();
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.distinct(true);
            }
            var categories = root.join("categories", JoinType.INNER);
            return cb.equal(categories.get("slug"), categorySlug);
        };
    }

    public static Specification<Content> isPublished() {
        return hasStatus(ContentStatus.PUBLISHED);
    }

    public static Specification<Content> isViewable() {
        return (root, query, cb) -> cb.and(
            cb.notEqual(root.get("status"), ContentStatus.ARCHIVED),
            cb.notEqual(root.get("status"), ContentStatus.REJECTED),
            cb.notEqual(root.get("status"), ContentStatus.DELETED)
        );
    }

    public static Specification<Content> hasAnyCategory(java.util.Collection<java.util.UUID> categoryIds) {
        return (root, query, cb) -> {
            if (categoryIds == null || categoryIds.isEmpty()) return cb.conjunction();
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.distinct(true);
            }
            var categories = root.join("categories", JoinType.INNER);
            return categories.get("id").in(categoryIds);
        };
    }

    public static Specification<Content> notInIds(java.util.Collection<java.util.UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.conjunction();
            return cb.not(root.get("id").in(ids));
        };
    }
}
