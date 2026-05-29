package com.lamastudio.backend.modules.auth.admin.service;

import com.lamastudio.backend.modules.auth.admin.entity.Admin;
import com.lamastudio.backend.modules.auth.admin.repository.AdminRepository;
import com.lamastudio.backend.shared.exception.AccountNotActiveException;
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
public class AdminUserDetailsServiceImpl implements UserDetailsService {

    private final AdminRepository adminRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Admin admin = adminRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Admin not found: " + email));
        return buildDetails(admin);
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserById(String id) {
        UUID uuid = UUID.fromString(id);
        Admin admin = adminRepository.findById(uuid)
                .orElseThrow(() -> new UsernameNotFoundException("Admin not found by id: " + id));
        return buildDetails(admin);
    }

    private UserDetails buildDetails(Admin admin) {
        if (!admin.isActive()) {
            throw new AccountNotActiveException("Admin account is " + admin.getAccountStatus().name().toLowerCase());
        }

        Set<GrantedAuthority> authorities = admin.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());

        return org.springframework.security.core.userdetails.User.builder()
                .username(admin.getId().toString())
                .password(admin.getPasswordHash())
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(!admin.isActive())
                .credentialsExpired(false)
                .disabled(!admin.isActive())
                .build();
    }
}
