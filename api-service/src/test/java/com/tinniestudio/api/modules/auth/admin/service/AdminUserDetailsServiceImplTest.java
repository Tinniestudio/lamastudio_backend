package com.tinniestudio.api.modules.auth.admin.service;

import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.modules.auth.admin.entity.AdminRoleName;
import com.tinniestudio.api.modules.auth.admin.repository.AdminRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression test: AdminRoleName only defines SUPER_ADMIN/MODERATOR, but nearly every admin
 * controller checks hasRole('ADMIN') as the base gate. Without a blanket ROLE_ADMIN grant, no
 * admin account of any role could ever pass that check — the entire admin business API was
 * unreachable regardless of which role an admin held.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserDetailsServiceImplTest {

    @Mock private AdminRepository adminRepository;
    @InjectMocks private AdminUserDetailsServiceImpl service;

    private Admin makeAdmin(AdminRoleName... roles) {
        Admin admin = new Admin();
        ReflectionTestUtils.setField(admin, "id", UUID.randomUUID());
        admin.setEmail("admin@test.com");
        admin.setPasswordHash("hash");
        for (AdminRoleName role : roles) {
            admin.getRoles().add(role);
        }
        return admin;
    }

    @Test
    void superAdmin_getsBaseAdminRoleInAdditionToSuperAdminRole() {
        Admin admin = makeAdmin(AdminRoleName.SUPER_ADMIN);
        when(adminRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));

        UserDetails details = service.loadUserByUsername("admin@test.com");

        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
    }

    @Test
    void moderator_getsBaseAdminRoleInAdditionToModeratorRole() {
        Admin admin = makeAdmin(AdminRoleName.MODERATOR);
        when(adminRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));

        UserDetails details = service.loadUserByUsername("admin@test.com");

        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_MODERATOR");
    }
}
