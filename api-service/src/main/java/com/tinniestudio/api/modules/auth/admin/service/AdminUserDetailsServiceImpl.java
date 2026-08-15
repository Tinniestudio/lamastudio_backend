package com.tinniestudio.api.modules.auth.admin.service;

import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.modules.auth.admin.repository.AdminRepository;
import com.tinniestudio.api.shared.exception.AccountNotActiveException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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

        // AdminRoleName only has SUPER_ADMIN/MODERATOR — neither ever grants "ROLE_ADMIN" on its
        // own, yet nearly every admin controller across the codebase requires exactly
        // hasRole('ADMIN') as the base admin check (SUPER_ADMIN/MODERATOR are only checked for a
        // handful of finer-grained, more sensitive endpoints). Every Admin entity is an admin, so
        // grant ROLE_ADMIN unconditionally alongside their specific role(s).
        Set<GrantedAuthority> authorities = new HashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        admin.getRoles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())));

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
