package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
// use project Category entity (same package)
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.tinniestudio.api.shared.entity.DomainEnums.*;

@Entity
@Table(name = "contents", indexes = {
        @Index(name = "idx_content_type",       columnList = "type"),
        @Index(name = "idx_content_status",     columnList = "status"),
        @Index(name = "idx_content_view_count", columnList = "view_count")
})
@Getter
@Setter
@NoArgsConstructor
public class Content extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String shortDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;

    private LocalDate releaseDate;

    private String language;

    private String country;

    @Column(nullable = false)
    private Boolean featured = false;

    private String posterUrl;

    private String thumbnailUrl;

    @Column(nullable = false)
    private UUID createdBy;

    private Instant publishedAt;

    @Column(nullable = false)
    private Long viewCount = 0L;

    @Column(nullable = false)
    private Boolean comingSoon = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaturityRating maturityRating = MaturityRating.NOT_RATED;

    @Column(nullable = false, precision = 3, scale = 2)
    private java.math.BigDecimal averageRating = java.math.BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer reviewCount = 0;

    private Integer durationSeconds;

    @ManyToMany
    @JoinTable(name = "content_categories", joinColumns = @JoinColumn(name = "content_id"), inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new HashSet<>();

    @OneToMany(mappedBy = "content", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Season> seasons = new ArrayList<>();

    @OneToMany(mappedBy = "content", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VideoAsset> videoAssets = new ArrayList<>();
}
