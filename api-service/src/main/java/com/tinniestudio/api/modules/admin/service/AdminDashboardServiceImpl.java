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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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

    private static final List<String> MONITORED_QUEUES = List.of(
        RabbitConfig.QUEUE_VIDEO_PROCESS,
        RabbitConfig.QUEUE_VIDEO_FAILED,
        RabbitConfig.QUEUE_NOTIFICATIONS,
        RabbitConfig.QUEUE_ANALYTICS_INGEST
    );

    @Override
    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant now = Instant.now();
        int dayOfMonth = now.atZone(ZoneOffset.UTC).getDayOfMonth();
        Instant startOfMonth = now.truncatedTo(ChronoUnit.DAYS)
            .minus((long) dayOfMonth - 1, ChronoUnit.DAYS);

        Map<String, Integer> queueDepths = new HashMap<>();
        for (String queue : MONITORED_QUEUES) {
            Properties props = rabbitAdmin.getQueueProperties(queue);
            int count = 0;
            if (props != null) {
                Object val = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                if (val instanceof Number n) count = n.intValue();
            }
            queueDepths.put(queue, count);
        }

        Long storageLong = uploadSessionRepo.sumFileSizeBytes();
        long storage = storageLong != null ? storageLong : 0L;

        BigDecimal revenue = paymentRepo.sumAmountAfter(startOfMonth);
        if (revenue == null) revenue = BigDecimal.ZERO;

        return new AdminDashboardResponse(
            userRepo.count(),
            userRepo.countByCreatedAtAfter(oneWeekAgo),
            subscriptionRepo.countByStatus(SubscriptionStatus.ACTIVE),
            revenue,
            contentRepo.countByStatus(ContentStatus.REVIEW),
            videoAssetRepo.countByProcessingStatus(ProcessingStatus.FAILED),
            storage,
            queueDepths
        );
    }
}
