package com.tinniestudio.api.modules.user.service;

import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ROLE_ADMIN') or #id == authentication.principal.username")
    public User getByIdSecured(UUID id) {
        return getById(id);
    }

    @Transactional
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public void suspendUser(UUID id) {
        User user = getById(id);
        user.setAccountStatus(AccountStatus.SUSPENDED);
        userRepository.save(user);
    }

    @Transactional
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public void deleteUser(UUID id) {
        User user = getById(id);
        user.softDelete();
        userRepository.save(user);
    }
}
