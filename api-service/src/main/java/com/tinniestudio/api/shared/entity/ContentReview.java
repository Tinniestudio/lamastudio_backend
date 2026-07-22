package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "content_reviews",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_review_user_content",
        columnNames = {"user_id", "content_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ContentReview extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(nullable = false)
    private Short rating;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.APPROVED;
}
