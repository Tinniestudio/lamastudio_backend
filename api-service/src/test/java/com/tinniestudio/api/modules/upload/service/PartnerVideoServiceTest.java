package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.upload.dto.PartnerVideoAssetResponse;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PartnerVideoService")
class PartnerVideoServiceTest {

    @Mock VideoAssetRepository videoAssetRepository;
    @Mock ContentRepository contentRepository;
    @Mock SeasonRepository seasonRepository;
    @Mock EpisodeRepository episodeRepository;
    @Mock VideoActivationService videoActivationService;
    @Mock com.tinniestudio.api.shared.storage.StorageService storageService;

    @InjectMocks PartnerVideoService partnerVideoService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();

    private Content ownedContent() {
        Content c = new Content();
        c.setId(contentId);
        c.setCreatedBy(ownerId);
        return c;
    }

    @Nested @DisplayName("listForTarget()")
    class ListForTargetTests {

        @Test @DisplayName("returns videos for own CONTENT target, most recent first as returned by the repository")
        void returnsForOwnedContent() {
            Content content = ownedContent();
            VideoAsset a1 = new VideoAsset();
            a1.setId(UUID.randomUUID());
            a1.setAssetType(VideoAssetType.MAIN_VIDEO);
            a1.setProcessingStatus(ProcessingStatus.READY);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(videoAssetRepository.findByContent_IdAndAssetTypeOrderByCreatedAtDesc(contentId, VideoAssetType.MAIN_VIDEO))
                .thenReturn(List.of(a1));

            List<PartnerVideoAssetResponse> result = partnerVideoService.listForTarget(
                ownerId, TargetEntityType.CONTENT, contentId, VideoAssetType.MAIN_VIDEO);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(a1.getId());
        }

        @Test @DisplayName("throws 404 when caller doesn't own the CONTENT target")
        void throws404WhenNotOwner() {
            Content content = new Content();
            content.setId(contentId);
            content.setCreatedBy(UUID.randomUUID());
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> partnerVideoService.listForTarget(
                    ownerId, TargetEntityType.CONTENT, contentId, VideoAssetType.MAIN_VIDEO))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test @DisplayName("returns videos for own EPISODE target via episode->season->content ownership")
        void returnsForOwnedEpisode() {
            UUID episodeId = UUID.randomUUID();
            Content content = ownedContent();
            Season season = new Season();
            season.setContent(content);
            Episode episode = new Episode();
            episode.setId(episodeId);
            episode.setSeason(season);
            VideoAsset a1 = new VideoAsset();
            a1.setId(UUID.randomUUID());
            a1.setAssetType(VideoAssetType.MAIN_VIDEO);
            when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
            when(videoAssetRepository.findByEpisode_IdAndAssetTypeOrderByCreatedAtDesc(episodeId, VideoAssetType.MAIN_VIDEO))
                .thenReturn(List.of(a1));

            List<PartnerVideoAssetResponse> result = partnerVideoService.listForTarget(
                ownerId, TargetEntityType.EPISODE, episodeId, VideoAssetType.MAIN_VIDEO);

            assertThat(result).hasSize(1);
        }
    }

    @Nested @DisplayName("activate()")
    class ActivateTests {

        @Test @DisplayName("delegates to VideoActivationService for a READY, content-linked asset owned by the caller")
        void activatesOwnedReadyAsset() {
            UUID assetId = UUID.randomUUID();
            Content content = ownedContent();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setContent(content);
            asset.setAssetType(VideoAssetType.MAIN_VIDEO);
            asset.setProcessingStatus(ProcessingStatus.READY);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.activate(ownerId, assetId);

            verify(videoActivationService).activateAndRetireSiblings(asset);
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(UUID.randomUUID());
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.activate(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verifyNoInteractions(videoActivationService);
        }

        @Test @DisplayName("throws 400 when the video isn't READY yet")
        void throws400WhenNotReady() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PROCESSING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.activate(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
            verifyNoInteractions(videoActivationService);
        }

        @Test @DisplayName("throws 400 when the video has no linked content")
        void throws400WhenNoContentLink() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            asset.setContent(null);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.activate(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
            verifyNoInteractions(videoActivationService);
        }

        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.activate(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested @DisplayName("cancel()")
    class CancelTests {

        @Test @DisplayName("sets CANCELLED on a PENDING video owned by the caller")
        void cancelsOwnedPendingAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(videoAssetRepository.save(any(VideoAsset.class))).thenAnswer(inv -> inv.getArgument(0));

            partnerVideoService.cancel(ownerId, assetId);

            assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.CANCELLED);
            verify(videoAssetRepository).save(asset);
        }

        @Test @DisplayName("sets CANCELLED on a PROCESSING video owned by the caller")
        void cancelsOwnedProcessingAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PROCESSING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(videoAssetRepository.save(any(VideoAsset.class))).thenAnswer(inv -> inv.getArgument(0));

            partnerVideoService.cancel(ownerId, assetId);

            assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.CANCELLED);
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(UUID.randomUUID());
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verify(videoAssetRepository, never()).save(any());
        }

        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test @DisplayName("throws 400 when the video is already READY")
        void throws400WhenAlreadyReady() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
            verify(videoAssetRepository, never()).save(any());
        }

        @Test @DisplayName("throws 400 when the video is already FAILED")
        void throws400WhenAlreadyFailed() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.FAILED);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }

        @Test @DisplayName("throws 400 when the video is already CANCELLED")
        void throws400WhenAlreadyCancelled() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.CANCELLED);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }
    }

    @Nested @DisplayName("delete()")
    class DeleteTests {

        @Test @DisplayName("deletes a READY video owned by the caller, including its raw storage object")
        void deletesOwnedReadyAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.delete(ownerId, assetId);

            verify(storageService).deleteObject("uploads/" + assetId + "/raw.mp4");
            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("deletes a FAILED video owned by the caller")
        void deletesOwnedFailedAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.FAILED);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.delete(ownerId, assetId);

            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("deletes a CANCELLED video owned by the caller")
        void deletesOwnedCancelledAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.CANCELLED);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.delete(ownerId, assetId);

            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("does not fail the request when storage deletion throws")
        void survivesStorageDeletionFailure() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            doThrow(new RuntimeException("S3 unreachable")).when(storageService).deleteObject(anyString());

            partnerVideoService.delete(ownerId, assetId);

            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(UUID.randomUUID());
            asset.setProcessingStatus(ProcessingStatus.READY);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verify(videoAssetRepository, never()).delete(any());
        }

        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test @DisplayName("throws 400 when the video is still PENDING")
        void throws400WhenPending() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
            verify(videoAssetRepository, never()).delete(any());
            verifyNoInteractions(storageService);
        }

        @Test @DisplayName("throws 400 when the video is still PROCESSING")
        void throws400WhenProcessing() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PROCESSING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }
    }
}
