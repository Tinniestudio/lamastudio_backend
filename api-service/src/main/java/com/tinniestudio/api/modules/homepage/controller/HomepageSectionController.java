package com.tinniestudio.api.modules.homepage.controller;

import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.service.HomepageSectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Homepage", description = "Homepage section configuration")
@RestController
@RequestMapping("/homepage-sections")
@RequiredArgsConstructor
public class HomepageSectionController {

    private final HomepageSectionService service;

    @Operation(summary = "List active homepage sections in display order")
    @GetMapping
    public ResponseEntity<List<SectionResponse>> listActive() {
        return ResponseEntity.ok(service.listActive());
    }
}
