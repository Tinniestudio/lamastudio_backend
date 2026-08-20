package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.VideoAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoActivationService")
class VideoActivationServiceTest {

    @Mock VideoAssetRepository videoAssetRepository;
    @InjectMocks VideoActivationService videoActivationService;

    @Test
    @DisplayName("content-only asset (movie): retires content-scoped siblings then activates and saves the given asset")
    void activatesAndRetiresContentScopedSiblings() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);

        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setAssetType(VideoAssetType.MAIN_VIDEO);
        asset.setContent(content);

        videoActivationService.activateAndRetireSiblings(asset);

        verify(videoAssetRepository).deactivateOtherAssetsForContent(contentId, VideoAssetType.MAIN_VIDEO, assetId);
        ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
        verify(videoAssetRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue()).isSameAs(asset);
    }

    @Test
    @DisplayName("episode-linked asset: retires episode-scoped siblings only, never falls back to the content-wide scope shared by every other episode of the same show")
    void activatesAndRetiresEpisodeScopedSiblingsOnly() {
        UUID assetId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        // Mirrors UploadService.linkTargetAndAssertOwnership()'s EPISODE case: both episode AND
        // content (denormalized from episode.getSeason().getContent()) are set on the asset.
        Content content = new Content();
        content.setId(contentId);

        Episode episode = new Episode();
        episode.setId(episodeId);

        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setAssetType(VideoAssetType.MAIN_VIDEO);
        asset.setEpisode(episode);
        asset.setContent(content);

        videoActivationService.activateAndRetireSiblings(asset);

        verify(videoAssetRepository).deactivateOtherAssetsForEpisode(episodeId, VideoAssetType.MAIN_VIDEO, assetId);
        verify(videoAssetRepository, never()).deactivateOtherAssetsForContent(any(), any(), any());
        verify(videoAssetRepository, never()).deactivateOtherAssetsForSeason(any(), any(), any());
        ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
        verify(videoAssetRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue()).isSameAs(asset);
    }
}
