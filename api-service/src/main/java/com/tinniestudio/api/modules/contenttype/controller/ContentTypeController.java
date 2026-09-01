package com.tinniestudio.api.modules.contenttype.controller;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.service.ContentTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Content Types", description = "Browse available content types")
@RestController
@RequestMapping("/content-types")
@RequiredArgsConstructor
public class ContentTypeController {

    private final ContentTypeService contentTypeService;

    @Operation(summary = "List all active content types")
    @GetMapping
    public ResponseEntity<List<ContentTypeResponse>> listActive() {
        return ResponseEntity.ok(contentTypeService.listActive());
    }
}
