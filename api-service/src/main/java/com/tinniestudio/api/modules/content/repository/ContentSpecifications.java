package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

public class ContentSpecifications {

    private ContentSpecifications() {}

    public static Specification<Content> hasStatus(ContentStatus status) {
        return (root, query, cb) -> status == null ? cb.conjunction()
            : cb.equal(root.get("status"), status);
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
            var categories = root.join("categories", JoinType.INNER);
            return cb.equal(categories.get("slug"), categorySlug);
        };
    }

    public static Specification<Content> isPublished() {
        return hasStatus(ContentStatus.PUBLISHED);
    }
}
