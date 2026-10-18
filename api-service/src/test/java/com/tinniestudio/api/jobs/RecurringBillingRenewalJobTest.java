package com.tinniestudio.api.jobs;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.modules.jobs.JobLogger;
import com.tinniestudio.api.modules.jobs.RecurringBillingRenewalJob;
import com.tinniestudio.api.shared.entity.UserSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringBillingRenewalJobTest {

    @Mock private UserSubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private JobLogger jobLogger;

    @InjectMocks private RecurringBillingRenewalJob job;

    @Test
    void run_processesEachDueSubscription() {
        UserSubscription a = new UserSubscription();
        a.setId(UUID.randomUUID());
        UserSubscription b = new UserSubscription();
        b.setId(UUID.randomUUID());

        when(subscriptionRepository.findByAutoRenewTrueAndNextRenewalAtLessThanEqual(any(Instant.class)))
            .thenReturn(List.of(a, b));
        when(jobLogger.start("RecurringBillingRenewalJob")).thenReturn(null);

        job.run();

        verify(subscriptionService).processRenewal(a.getId());
        verify(subscriptionService).processRenewal(b.getId());
    }

    @Test
    void run_oneSubscriptionFailing_doesNotStopTheOthers() {
        UserSubscription a = new UserSubscription();
        a.setId(UUID.randomUUID());
        UserSubscription b = new UserSubscription();
        b.setId(UUID.randomUUID());

        when(subscriptionRepository.findByAutoRenewTrueAndNextRenewalAtLessThanEqual(any(Instant.class)))
            .thenReturn(List.of(a, b));
        when(jobLogger.start("RecurringBillingRenewalJob")).thenReturn(null);
        doThrow(new RuntimeException("stripe blew up")).when(subscriptionService).processRenewal(a.getId());

        job.run();

        verify(subscriptionService).processRenewal(a.getId());
        verify(subscriptionService).processRenewal(b.getId());
    }
}
