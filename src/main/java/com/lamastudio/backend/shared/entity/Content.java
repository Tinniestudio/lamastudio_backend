package com.lamastudio.backend.shared.entity;

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

import com.lamastudio.backend.shared.entity.DomainEnums.*;

@Entity
@Table(name = "contents", indexes = {
        @Index(name = "idx_content_slug", columnList = "slug", unique = true),
        @Index(name = "idx_content_type", columnList = "type"),
        @Index(name = "idx_content_status", columnList = "status")
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

    private Boolean featured = false;

    private String posterUrl;

    private String thumbnailUrl;

    @Column(nullable = false)
    private UUID createdBy;

    private Instant publishedAt;

    @ManyToMany
    @JoinTable(name = "content_categories", joinColumns = @JoinColumn(name = "content_id"), inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new HashSet<>();

    @OneToMany(mappedBy = "content", cascade = CascadeType.ALL)
    private List<Season> seasons = new ArrayList<>();

    @OneToMany(mappedBy = "content", cascade = CascadeType.ALL)
    private List<VideoAsset> videoAssets = new ArrayList<>();
}
