package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private final UserRepository userRepo;
    private final UserSubscriptionRepository subscriptionRepo;
    private final PaymentRepository paymentRepo;
    private final ContentRepository contentRepo;
    private final VideoAssetRepository videoAssetRepo;
    private final UploadSessionRepository uploadSessionRepo;
    private final RabbitAdmin rabbitAdmin;
    @Qualifier("dashboardTaskExecutor")
    private final Executor dashboardTaskExecutor;

    private static final List<String> MONITORED_QUEUES = List.of(
        RabbitConfig.QUEUE_VIDEO_PROCESS,
        RabbitConfig.QUEUE_VIDEO_FAILED,
        RabbitConfig.QUEUE_NOTIFICATIONS,
        RabbitConfig.QUEUE_ANALYTICS_INGEST
    );

    /**
     * No outer @Transactional here on purpose: the ~11 lookups below are dispatched to separate
     * threads via dashboardTaskExecutor so they run concurrently against separate pooled
     * connections. A single JPA EntityManager/transaction is bound to one thread and isn't safe
     * to share across threads, and these figures have no need for snapshot consistency with each
     * other anyway (they're independent counts/sums assembled into one response) — each
     * Spring Data repository call still runs in its own short transaction as usual.
     */
    @Override
    public AdminDashboardResponse getDashboard() {
        Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant now = Instant.now();
        int dayOfMonth = now.atZone(ZoneOffset.UTC).getDayOfMonth();
        Instant startOfMonth = now.truncatedTo(ChronoUnit.DAYS)
            .minus((long) dayOfMonth - 1, ChronoUnit.DAYS);

        Map<String, Integer> queueDepths = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> queueFutures = MONITORED_QUEUES.stream()
            .map(queue -> CompletableFuture.runAsync(() -> queueDepths.put(queue, queueDepth(queue)), dashboardTaskExecutor))
            .toList();

        CompletableFuture<Long> userCount = CompletableFuture.supplyAsync(userRepo::count, dashboardTaskExecutor);
        CompletableFuture<Long> newUsers = CompletableFuture.supplyAsync(
            () -> userRepo.countByCreatedAtAfter(oneWeekAgo), dashboardTaskExecutor);
        CompletableFuture<Long> activeSubscriptions = CompletableFuture.supplyAsync(
            () -> subscriptionRepo.countByStatus(SubscriptionStatus.ACTIVE), dashboardTaskExecutor);
        CompletableFuture<BigDecimal> revenue = CompletableFuture.supplyAsync(
            () -> paymentRepo.sumAmountAfter(startOfMonth), dashboardTaskExecutor);
        CompletableFuture<Long> contentInReview = CompletableFuture.supplyAsync(
            () -> contentRepo.countByStatus(ContentStatus.REVIEW), dashboardTaskExecutor);
        CompletableFuture<Long> failedAssets = CompletableFuture.supplyAsync(
            () -> videoAssetRepo.countByProcessingStatus(ProcessingStatus.FAILED), dashboardTaskExecutor);
        CompletableFuture<Long> storage = CompletableFuture.supplyAsync(
            uploadSessionRepo::sumFileSizeBytes, dashboardTaskExecutor);

        CompletableFuture.allOf(
            java.util.stream.Stream.concat(
                queueFutures.stream(),
                java.util.stream.Stream.of(userCount, newUsers, activeSubscriptions, revenue,
                    contentInReview, failedAssets, storage)
            ).toArray(CompletableFuture[]::new)
        ).join();

        BigDecimal revenueValue = revenue.join();
        Long storageValue = storage.join();

        return new AdminDashboardResponse(
            userCount.join(),
            newUsers.join(),
            activeSubscriptions.join(),
            revenueValue != null ? revenueValue : BigDecimal.ZERO,
            contentInReview.join(),
            failedAssets.join(),
            storageValue != null ? storageValue : 0L,
            queueDepths
        );
    }

    private int queueDepth(String queue) {
        Properties props = rabbitAdmin.getQueueProperties(queue);
        if (props == null) return 0;
        Object val = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        return val instanceof Number n ? n.intValue() : 0;
    }
}
