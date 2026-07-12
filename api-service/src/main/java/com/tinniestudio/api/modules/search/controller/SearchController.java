package com.tinniestudio.api.modules.search.controller;

import com.tinniestudio.api.modules.search.dto.SearchRequest;
import com.tinniestudio.api.modules.search.dto.SearchResponse;
import com.tinniestudio.api.modules.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search", description = "Full-text content search")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "Search published content by title and description")
    @GetMapping
    public ResponseEntity<SearchResponse> search(@Valid @ModelAttribute SearchRequest request) {
        return ResponseEntity.ok(searchService.search(request));
    }
}
