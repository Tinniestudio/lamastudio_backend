package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.VideoAssetAdminResponse;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin - Uploads", description = "Admin upload and processing management")
@RestController
@RequestMapping("/admin/uploads")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUploadController {

    private final VideoAssetRepository videoAssetRepository;

    @Operation(summary = "List uploads currently processing or failed")
    @GetMapping("/processing")
    public ResponseEntity<Page<VideoAssetAdminResponse>> getProcessing(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
            videoAssetRepository.findByProcessingStatusInOrderByCreatedAtDesc(
                List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED),
                pageable
            ).map(VideoAssetAdminResponse::from)
        );
    }
}
