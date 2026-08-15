package com.tinniestudio.api.modules.user.service;

import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.Role;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.AccountNotActiveException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock UserRepository userRepository;
    @InjectMocks UserDetailsServiceImpl service;

    private User makeUser(UUID id, AccountStatus status) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("user@test.com");
        u.setPasswordHash("hash");
        u.setAccountStatus(status);
        HashSet<Role> roles = new HashSet<>();
        roles.add(new Role(RoleName.ROLE_USER));
        u.setRoles(roles);
        return u;
    }

    @Test
    void loadUserById_suspended_throwsAccountNotActive() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(makeUser(id, AccountStatus.SUSPENDED)));

        assertThatThrownBy(() -> service.loadUserById(id.toString()))
            .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void loadSuspendedUserById_suspended_authenticatesSuccessfully() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(makeUser(id, AccountStatus.SUSPENDED)));

        UserDetails details = service.loadSuspendedUserById(id.toString());

        assertThat(details.getUsername()).isEqualTo(id.toString());
    }

    @Test
    void loadSuspendedUserById_banned_stillThrowsAccountNotActive() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(makeUser(id, AccountStatus.BAN)));

        assertThatThrownBy(() -> service.loadSuspendedUserById(id.toString()))
            .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void loadSuspendedUserById_deleted_stillThrowsAccountNotActive() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(makeUser(id, AccountStatus.DELETED)));

        assertThatThrownBy(() -> service.loadSuspendedUserById(id.toString()))
            .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void loadSuspendedUserById_active_stillThrowsAccountNotActive() {
        // ACTIVE users authenticate via the normal loadUserById() path; this permissive loader
        // is only ever invoked as a fallback after loadUserById() already failed, so it should
        // never be asked to accept an ACTIVE account, but verify it doesn't accidentally do so.
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(makeUser(id, AccountStatus.ACTIVE)));

        assertThatThrownBy(() -> service.loadSuspendedUserById(id.toString()))
            .isInstanceOf(AccountNotActiveException.class);
    }
}
