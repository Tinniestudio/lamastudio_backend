package com.tinniestudio.api.modules.auth.admin.service;

import com.tinniestudio.api.modules.auth.admin.dto.AdminBootstrapRequest;
import com.tinniestudio.api.modules.auth.admin.dto.AdminAuthResponse;
import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.modules.auth.admin.entity.AdminRoleName;
import com.tinniestudio.api.modules.auth.admin.repository.AdminRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    private final AtomicBoolean bootstrapUsed = new AtomicBoolean(false);

    @Transactional
    public AdminAuthResponse bootstrapSuperAdmin(AdminBootstrapRequest request) {
        String configuredToken = appProperties.getAdminBootstrapToken();

        if (!StringUtils.hasText(configuredToken)) {
            throw new BadRequestException("Bootstrap endpoint is disabled");
        }

        if (bootstrapUsed.get() || adminRepository.existsByRolesContaining(AdminRoleName.SUPER_ADMIN)) {
            throw new com.tinniestudio.api.shared.exception.ResourceNotFoundException(
                    "Bootstrap endpoint is no longer available");
        }

        if (!configuredToken.equals(request.getBootstrapToken())) {
            throw new BadRequestException("Invalid bootstrap token");
        }

        Admin admin = new Admin();
        admin.setEmail(request.getEmail().toLowerCase().strip());
        admin.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        admin.getRoles().add(AdminRoleName.SUPER_ADMIN);

        admin = adminRepository.save(admin);
        bootstrapUsed.set(true);

        log.info("Super admin bootstrapped: {}", admin.getEmail());

        return AdminAuthResponse.builder()
                .adminId(admin.getId())
                .email(admin.getEmail())
                .roles(admin.getRoles())
                .message("Super admin created successfully")
                .build();
    }

    public boolean isBootstrapAvailable() {
        String token = appProperties.getAdminBootstrapToken();
        return StringUtils.hasText(token)
                && !bootstrapUsed.get()
                && !adminRepository.existsByRolesContaining(AdminRoleName.SUPER_ADMIN);
    }
}
