package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentRepository extends JpaRepository<Content, UUID>, JpaSpecificationExecutor<Content> {
    Optional<Content> findBySlug(String slug);

    long countByStatus(ContentStatus status);

    long countByCreatedByAndStatus(UUID createdBy, ContentStatus status);

    @Query("SELECT COALESCE(SUM(c.viewCount), 0) FROM Content c WHERE c.createdBy = :createdBy")
    Long sumViewCountByCreatedBy(@Param("createdBy") UUID createdBy);

    List<Content> findByCreatedBy(UUID createdBy);

    Page<Content> findByCreatedByOrderByCreatedAtDesc(UUID createdBy, Pageable pageable);

    Page<Content> findByStatusOrderByCreatedAtDesc(ContentStatus status, Pageable pageable);

    Page<Content> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE Content c SET c.viewCount = c.viewCount + 1 WHERE c.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    @Query(
        value = "SELECT c.id, c.title, c.slug, c.description, c.short_description," +
                " c.content_type_id, c.status, c.maturity_rating, c.release_date, c.language, c.country," +
                " c.featured, c.poster_url, c.thumbnail_url, c.created_by, c.published_at," +
                " c.view_count, c.coming_soon, c.duration_seconds, c.created_at, c.updated_at," +
                " c.average_rating, c.review_count, c.deleted_at" +
                " FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))" +
                " ORDER BY ts_rank(c.search_vector, plainto_tsquery('english', :q)) DESC",
        countQuery =
                "SELECT count(*) FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))",
        nativeQuery = true
    )
    Page<Content> searchByRelevance(
        @Param("q") String q,
        @Param("type") String type,
        @Param("language") String language,
        @Param("country") String country,
        @Param("categorySlug") String categorySlug,
        Pageable pageable
    );

    @Query(
        value = "SELECT c.id, c.title, c.slug, c.description, c.short_description," +
                " c.content_type_id, c.status, c.maturity_rating, c.release_date, c.language, c.country," +
                " c.featured, c.poster_url, c.thumbnail_url, c.created_by, c.published_at," +
                " c.view_count, c.coming_soon, c.duration_seconds, c.created_at, c.updated_at," +
                " c.average_rating, c.review_count, c.deleted_at" +
                " FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))" +
                " ORDER BY c.published_at DESC NULLS LAST",
        countQuery =
                "SELECT count(*) FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))",
        nativeQuery = true
    )
    Page<Content> searchByLatest(
        @Param("q") String q,
        @Param("type") String type,
        @Param("language") String language,
        @Param("country") String country,
        @Param("categorySlug") String categorySlug,
        Pageable pageable
    );

    @Query(
        value = "SELECT c.id, c.title, c.slug, c.description, c.short_description," +
                " c.content_type_id, c.status, c.maturity_rating, c.release_date, c.language, c.country," +
                " c.featured, c.poster_url, c.thumbnail_url, c.created_by, c.published_at," +
                " c.view_count, c.coming_soon, c.duration_seconds, c.created_at, c.updated_at," +
                " c.average_rating, c.review_count, c.deleted_at" +
                " FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))" +
                " ORDER BY c.view_count DESC",
        countQuery =
                "SELECT count(*) FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))",
        nativeQuery = true
    )
    Page<Content> searchByPopular(
        @Param("q") String q,
        @Param("type") String type,
        @Param("language") String language,
        @Param("country") String country,
        @Param("categorySlug") String categorySlug,
        Pageable pageable
    );
}
