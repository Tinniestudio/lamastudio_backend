package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface FavoriteService {
    void add(UUID userId, UUID contentId);
    void remove(UUID userId, UUID contentId);
    Page<FavoriteResponse> list(UUID userId, Pageable pageable);
}
