package com.tinniestudio.api.user.service;

import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserService;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("getById returns user when found")
    void getById_found() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        User result = userService.getById(id);

        assertThat(result).isSameAs(user);
        verify(userRepository).findById(id);
    }

    @Test
    @DisplayName("getById throws when not found")
    void getById_notFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("suspendUser sets account status to SUSPENDED and saves")
    void suspendUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setAccountStatus(AccountStatus.ACTIVE);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.suspendUser(id);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    @DisplayName("deleteUser performs soft delete and saves")
    void deleteUser() {
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setAccountStatus(AccountStatus.ACTIVE);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.deleteUser(id);

        verify(userRepository).save(any(User.class));
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.DELETED);
        assertThat(user.getDeletedAt()).isNotNull();
    }
}
