package com.tinniestudio.api.modules.reviews.repository;

import com.tinniestudio.api.shared.entity.ContentReview;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<ContentReview, UUID> {
    Page<ContentReview> findByContentIdAndStatusOrderByCreatedAtDesc(UUID contentId, ReviewStatus status, Pageable pageable);
    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);
    Optional<ContentReview> findByIdAndUserId(UUID id, UUID userId);
}
