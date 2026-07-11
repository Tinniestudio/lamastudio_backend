package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.repository.WatchProgressRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock ContentRepository contentRepo;
    @Mock UserSubscriptionRepository subscriptionRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock WatchProgressRepository watchProgressRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock RabbitTemplate rabbitTemplate;

    private PlaybackServiceImpl service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCdn().setBaseUrl("http://cdn.test");
        service = new PlaybackServiceImpl(
            contentRepo, subscriptionRepo, videoAssetRepo,
            watchProgressRepo, episodeRepo, rabbitTemplate, props
        );
    }

    @Nested
    class checkAccess {

        @Test
        void deniesWhenContentNotPublished() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));

            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());

            assertThat(resp.isHasAccess()).isFalse();
            assertThat(resp.getReason()).isEqualTo("CONTENT_NOT_PUBLISHED");
        }

        @Test
        void deniesWhenNoActiveSubscription() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(eq(userId), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.empty());

            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());

            assertThat(resp.isHasAccess()).isFalse();
            assertThat(resp.getReason()).isEqualTo("NO_ACTIVE_SUBSCRIPTION");
        }

        @Test
        void grantsWhenPublishedAndSubscriptionActive() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            UserSubscription sub = new UserSubscription();
            sub.setStatus(SubscriptionStatus.ACTIVE);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(eq(userId), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(sub));

            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());

            assertThat(resp.isHasAccess()).isTrue();
        }
    }

    @Nested
    class getContentManifest {

        @Test
        void throwsWhenAccessDenied() {
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> service.getContentManifest(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void throwsWhenNoReadyVideoAsset() {
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            UserSubscription sub = new UserSubscription();
            sub.setStatus(SubscriptionStatus.ACTIVE);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(any(), any())).thenReturn(Optional.of(sub));
            when(videoAssetRepo.findTopByContent_IdAndAssetTypeAndProcessingStatus(
                any(), eq(VideoAssetType.MAIN_VIDEO), eq(ProcessingStatus.READY)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getContentManifest(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }

        @Test
        void returnsManifestWithSubtitlesAndResumeAt() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);

            UserSubscription sub = new UserSubscription();
            sub.setStatus(SubscriptionStatus.ACTIVE);

            Subtitle subtitle = new Subtitle();
            subtitle.setLanguageCode("en");
            subtitle.setLabel("English");
            subtitle.setFileUrl("subs/en.vtt");
            subtitle.setIsDefault(false);   // Boolean field — Lombok generates setIsDefault()

            VideoAsset asset = new VideoAsset();
            asset.setManifestUrl("processed/abc/master.m3u8");
            asset.setDurationSeconds(3600);
            asset.setSubtitles(List.of(subtitle));

            WatchProgress progress = new WatchProgress();
            progress.setProgressSeconds(120);

            when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(eq(userId), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(sub));
            when(videoAssetRepo.findTopByContent_IdAndAssetTypeAndProcessingStatus(
                eq(contentId), eq(VideoAssetType.MAIN_VIDEO), eq(ProcessingStatus.READY)))
                .thenReturn(Optional.of(asset));
            when(watchProgressRepo.findMovieProgress(userId, contentId))
                .thenReturn(Optional.of(progress));

            PlaybackManifestResponse resp = service.getContentManifest(userId, contentId);

            assertThat(resp.getManifestUrl()).isEqualTo("http://cdn.test/processed/abc/master.m3u8");
            assertThat(resp.getDuration()).isEqualTo(3600);
            assertThat(resp.getResumeAt()).isEqualTo(120);
            assertThat(resp.getSubtitles()).hasSize(1);
            assertThat(resp.getSubtitles().get(0).getLanguageCode()).isEqualTo("en");
        }
    }

    @Nested
    class recordProgress {

        @Test
        void throwsWhenNeitherContentIdNorEpisodeIdProvided() {
            ProgressRequest req = new ProgressRequest();
            req.setProgressSeconds(60);
            req.setDurationSeconds(3600);

            assertThatThrownBy(() -> service.recordProgress(UUID.randomUUID(), req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }

        @Test
        void throwsWhenDurationSecondsIsZero() {
            ProgressRequest req = new ProgressRequest();
            req.setContentId(UUID.randomUUID());
            req.setProgressSeconds(60);
            req.setDurationSeconds(0);

            assertThatThrownBy(() -> service.recordProgress(UUID.randomUUID(), req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }

        @Test
        void upsertsNewMovieProgressRecord() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            ProgressRequest req = new ProgressRequest();
            req.setContentId(contentId);
            req.setProgressSeconds(900);
            req.setDurationSeconds(3600);

            when(watchProgressRepo.findMovieProgress(userId, contentId)).thenReturn(Optional.empty());
            when(watchProgressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.recordProgress(userId, req);

            ArgumentCaptor<WatchProgress> captor = ArgumentCaptor.forClass(WatchProgress.class);
            verify(watchProgressRepo).save(captor.capture());
            WatchProgress saved = captor.getValue();
            assertThat(saved.getProgressSeconds()).isEqualTo(900);
            assertThat(saved.getCompletionPercentage()).isEqualByComparingTo("25.00");
            assertThat(saved.getCompleted()).isFalse();
        }

        @Test
        void setsCompletedTrueWhenProgressExceeds90Percent() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            ProgressRequest req = new ProgressRequest();
            req.setContentId(contentId);
            req.setProgressSeconds(3300);
            req.setDurationSeconds(3600);

            when(watchProgressRepo.findMovieProgress(userId, contentId)).thenReturn(Optional.empty());
            when(watchProgressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.recordProgress(userId, req);

            ArgumentCaptor<WatchProgress> captor = ArgumentCaptor.forClass(WatchProgress.class);
            verify(watchProgressRepo).save(captor.capture());
            assertThat(captor.getValue().getCompleted()).isTrue();
        }

        @Test
        void updatesExistingMovieProgressRecord() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            WatchProgress existing = new WatchProgress();
            existing.setUserId(userId);
            existing.setContentId(contentId);
            existing.setProgressSeconds(100);
            existing.setDurationSeconds(3600);

            ProgressRequest req = new ProgressRequest();
            req.setContentId(contentId);
            req.setProgressSeconds(500);
            req.setDurationSeconds(3600);

            when(watchProgressRepo.findMovieProgress(userId, contentId)).thenReturn(Optional.of(existing));
            when(watchProgressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.recordProgress(userId, req);

            ArgumentCaptor<WatchProgress> captor = ArgumentCaptor.forClass(WatchProgress.class);
            verify(watchProgressRepo).save(captor.capture());
            assertThat(captor.getValue().getProgressSeconds()).isEqualTo(500);
        }
    }

    @Nested
    class getContinueWatching {

        @Test
        void returnsEmptyListWhenNoProgress() {
            UUID userId = UUID.randomUUID();
            when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
                .thenReturn(List.of());

            List<ContinueWatchingItem> result = service.getContinueWatching(userId);

            assertThat(result).isEmpty();
        }

        @Test
        void mapsMovieProgressToItem() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            WatchProgress p = new WatchProgress();
            p.setContentId(contentId);
            p.setProgressSeconds(300);
            p.setDurationSeconds(3600);
            p.setCompletionPercentage(new java.math.BigDecimal("8.33"));
            p.setLastWatchedAt(java.time.Instant.now());

            Content content = new Content();
            content.setTitle("My Movie");
            content.setId(contentId);

            when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
                .thenReturn(List.of(p));
            when(contentRepo.findAllById(any())).thenReturn(List.of(content));
            when(episodeRepo.findAllById(any())).thenReturn(List.of());

            List<ContinueWatchingItem> result = service.getContinueWatching(userId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProgressSeconds()).isEqualTo(300);
            assertThat(result.get(0).getTitle()).isEqualTo("My Movie");
        }
    }
}
