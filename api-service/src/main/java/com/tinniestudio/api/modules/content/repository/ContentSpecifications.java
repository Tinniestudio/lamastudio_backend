package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
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

    public static Specification<Content> hasType(String contentTypeSlug) {
        return (root, query, cb) -> contentTypeSlug == null ? cb.conjunction()
            : cb.equal(root.join("contentType", JoinType.INNER).get("slug"), contentTypeSlug);
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

    /**
     * AND semantics: content must belong to ALL given category slugs, not just one of them.
     * One join per slug — each loop iteration gets its own alias in Hibernate's Criteria API, so
     * N slugs correctly requires N distinct category memberships (the standard tag-AND-filter
     * pattern). Contrast with hasAnyCategory, which is OR-by-id and used elsewhere.
     */
    public static Specification<Content> hasCategories(java.util.List<String> slugs) {
        return (root, query, cb) -> {
            if (slugs == null || slugs.isEmpty()) return cb.conjunction();
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.distinct(true);
            }
            var predicates = slugs.stream()
                .map(slug -> cb.equal(root.join("categories", JoinType.INNER).get("slug"), slug))
                .toArray(jakarta.persistence.criteria.Predicate[]::new);
            return cb.and(predicates);
        };
    }
}
