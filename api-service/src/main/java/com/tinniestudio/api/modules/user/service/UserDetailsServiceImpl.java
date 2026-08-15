package com.tinniestudio.api.modules.user.service;

import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.AccountNotActiveException;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        validateAccountActive(user);

        return buildUserDetails(user);
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserById(String id) {
        UUID uuid = UUID.fromString(id);
        User user = userRepository.findById(uuid)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + id));

        validateAccountActive(user);

        return buildUserDetails(user);
    }

    /**
     * Used ONLY by JwtAuthenticationFilter when the request path is the appeal-submission
     * endpoint (POST /appeals). A SUSPENDED account otherwise cannot authenticate at all — see
     * validateAccountActive()/loadUserById() — but suspension appeals exist specifically to
     * recover from suspension, so that one endpoint must remain reachable. BAN and DELETED
     * accounts are still rejected here: BAN must "block user from total access" (Batch 14 #2),
     * and DELETED has no path back.
     */
    @Transactional(readOnly = true)
    public UserDetails loadSuspendedUserById(String id) {
        UUID uuid = UUID.fromString(id);
        User user = userRepository.findById(uuid)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + id));

        if (user.getAccountStatus() != AccountStatus.SUSPENDED) {
            throw new AccountNotActiveException("Account is " + user.getAccountStatus().name().toLowerCase());
        }

        return buildUserDetails(user);
    }

    private void validateAccountActive(User user) {
        if (!user.isActive()) {
            throw new AccountNotActiveException("Account is " + user.getAccountStatus().name().toLowerCase());
        }
    }

    private UserDetails buildUserDetails(User user) {
        Set<GrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().name()))
                .collect(Collectors.toSet());

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getId().toString())
                .password(user.getPasswordHash() != null ? user.getPasswordHash() : "")
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(!user.isActive())
                .credentialsExpired(false)
                .disabled(!user.isActive())
                .build();
    }
}
