package com.tinniestudio.api.modules.auth.user.service;

import com.tinniestudio.api.modules.auth.user.entity.UserSession;
import com.tinniestudio.api.modules.auth.user.repository.UserSessionRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.UserSubscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceImplTest {

    @Mock private UserSessionRepository userSessionRepository;
    @Mock private UserSubscriptionRepository userSubscriptionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CacheService cacheService;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionServiceImpl(
                userSessionRepository, userSubscriptionRepository, passwordEncoder, cacheService);
    }

    @Test
    @DisplayName("createSession no longer evicts — just creates, even past the old limit")
    void createSession_doesNotEvictAnyoneRegardlessOfCount() {
        UUID userId = UUID.randomUUID();
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userSessionRepository.save(any(UserSession.class))).thenAnswer(inv -> {
            UserSession s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/100.0");

        sessionService.createSession(userId, "raw-token", request);

        verify(userSessionRepository, never()).findFirstByUserIdAndRevokedFalseOrderByCreatedAtAsc(any());
        verify(userSessionRepository, never()).save(argThat(UserSession::isRevoked));
    }

    @Test
    @DisplayName("countActiveSessions delegates to the repository's active count")
    void countActiveSessions_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        when(userSessionRepository.countByUserIdAndRevokedFalse(userId)).thenReturn(3L);

        assertThat(sessionService.countActiveSessions(userId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("resolveMaxDevicesForUser reads the subscription's snapshotted maxDevices")
    void resolveMaxDevicesForUser_readsSnapshottedValue() {
        UUID userId = UUID.randomUUID();
        UserSubscription sub = new UserSubscription();
        sub.setMaxDevices(3);
        when(userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));

        assertThat(sessionService.resolveMaxDevicesForUser(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("resolveMaxDevicesForUser defaults to 1 when there is no active subscription")
    void resolveMaxDevicesForUser_defaultsToOne() {
        UUID userId = UUID.randomUUID();
        when(userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(sessionService.resolveMaxDevicesForUser(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("selfRevokeSession: 404s when the session doesn't belong to the caller")
    void selfRevokeSession_wrongOwner_notFound() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(userSessionRepository.findByUserIdAndId(userId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.selfRevokeSession(userId, sessionId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Session not found");
    }

    @Test
    @DisplayName("selfRevokeSession: already-revoked session is an idempotent no-op")
    void selfRevokeSession_alreadyRevoked_isNoOp() {
        UUID userId = UUID.randomUUID();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.revoke(null);
        when(userSessionRepository.findByUserIdAndId(userId, session.getId())).thenReturn(Optional.of(session));

        sessionService.selfRevokeSession(userId, session.getId(), null);

        verify(userSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("selfRevokeSession: rejects revoking the caller's own current session")
    void selfRevokeSession_currentSession_rejected() {
        UUID userId = UUID.randomUUID();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        when(userSessionRepository.findByUserIdAndId(userId, session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.selfRevokeSession(userId, session.getId(), session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("current session");
    }

    @Test
    @DisplayName("selfRevokeSession: revokes a different, active, owned session and clears its cache key")
    void selfRevokeSession_success() {
        UUID userId = UUID.randomUUID();
        UUID currentSessionId = UUID.randomUUID();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        when(userSessionRepository.findByUserIdAndId(userId, session.getId())).thenReturn(Optional.of(session));

        sessionService.selfRevokeSession(userId, session.getId(), currentSessionId);

        assertThat(session.isRevoked()).isTrue();
        verify(userSessionRepository).save(session);
        verify(cacheService).delete(contains(session.getId().toString()));
    }
}
