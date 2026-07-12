package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.repository.WatchHistoryRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.WatchHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WatchHistoryServiceImpl implements WatchHistoryService {

    private final WatchHistoryRepository historyRepo;
    private final ContentRepository contentRepo;

    @Override
    @Transactional(readOnly = true)
    public Page<WatchHistoryResponse> list(UUID userId, Pageable pageable) {
        Page<WatchHistory> historyPage = historyRepo.findByUserIdOrderByWatchedAtDesc(userId, pageable);

        List<UUID> contentIds = historyPage.getContent().stream()
                .map(WatchHistory::getContentId)
                .toList();

        Map<UUID, ContentSummaryResponse> contentMap = contentRepo.findAllById(contentIds).stream()
                .collect(Collectors.toMap(Content::getId, ContentSummaryResponse::from));

        return historyPage.map(entry -> new WatchHistoryResponse(
                entry.getId(),
                entry.getContentId(),
                entry.getEpisodeId(),
                entry.getWatchedAt(),
                entry.getProgressSeconds(),
                entry.getDurationSeconds(),
                entry.getDeviceType(),
                contentMap.get(entry.getContentId())
        ));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID historyId) {
        WatchHistory entry = historyRepo.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Watch history entry not found"));
        historyRepo.delete(entry);
    }

    @Override
    @Transactional
    public void deleteAll(UUID userId) {
        historyRepo.deleteAllByUserId(userId);
    }
}
