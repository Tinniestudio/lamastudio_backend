package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock UserRepository userRepo;
    @Mock UserSubscriptionRepository subscriptionRepo;
    @Mock PaymentRepository paymentRepo;
    @Mock ContentRepository contentRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock RabbitAdmin rabbitAdmin;
    @InjectMocks AdminDashboardServiceImpl dashboardService;

    @Test
    void getDashboard_returnsAggregatedStats() {
        when(userRepo.count()).thenReturn(500L);
        when(userRepo.countByCreatedAtAfter(any(Instant.class))).thenReturn(12L);
        when(subscriptionRepo.countByStatus(any())).thenReturn(200L);
        when(paymentRepo.sumAmountAfter(any(Instant.class))).thenReturn(new BigDecimal("5000.00"));
        when(contentRepo.countByStatus(any())).thenReturn(3L);
        when(videoAssetRepo.countByProcessingStatus(any())).thenReturn(2L);
        when(uploadSessionRepo.sumFileSizeBytes()).thenReturn(1_000_000_000L);
        when(rabbitAdmin.getQueueProperties(any())).thenReturn(null); // null = queue not reachable -> 0

        AdminDashboardResponse result = dashboardService.getDashboard();

        assertThat(result.totalUsers()).isEqualTo(500L);
        assertThat(result.newUsersThisWeek()).isEqualTo(12L);
        assertThat(result.activeSubscriptions()).isEqualTo(200L);
        assertThat(result.revenueThisMonth()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.storageBytesUsed()).isEqualTo(1_000_000_000L);
        assertThat(result.queueDepths()).isNotNull();
    }
}
