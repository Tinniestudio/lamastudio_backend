package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface WatchHistoryService {
    Page<WatchHistoryResponse> list(UUID userId, Pageable pageable);
    void delete(UUID userId, UUID historyId);
    void deleteAll(UUID userId);
}
