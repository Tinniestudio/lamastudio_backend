package com.lamastudio.backend.user.service;

import com.lamastudio.backend.exception.ResourceNotFoundException;
import com.lamastudio.backend.user.entity.User;
import com.lamastudio.backend.user.repository.UserRepository;
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
        user.setAccountStatus(com.lamastudio.backend.user.entity.AccountStatus.SUSPENDED);
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
