package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
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
    @DisplayName("retires every sibling then activates and saves the given asset")
    void activatesAndRetiresSiblings() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);

        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setAssetType(VideoAssetType.MAIN_VIDEO);
        asset.setContent(content);

        videoActivationService.activateAndRetireSiblings(asset);

        verify(videoAssetRepository).deactivateOtherAssets(contentId, VideoAssetType.MAIN_VIDEO, assetId);
        ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
        verify(videoAssetRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue()).isSameAs(asset);
    }
}
