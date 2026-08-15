package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.role.repository.RoleRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.entity.Role;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerPromotionServiceImplTest {

    @Mock UserRepository userRepo;
    @Mock RoleRepository roleRepo;
    @Mock PartnerProfileRepository profileRepo;
    @InjectMocks PartnerPromotionServiceImpl promotionService;

    private User makeUser(UUID id) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("user@test.com");
        u.setRoles(new HashSet<>());
        return u;
    }

    @Test
    void grantPartnerRoleAndProfile_grantsRoleAndCreatesProfileWhenAbsent() {
        UUID userId = UUID.randomUUID();
        User user = makeUser(userId);
        Role partnerRole = new Role(RoleName.ROLE_PARTNER);

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(RoleName.ROLE_PARTNER)).thenReturn(Optional.of(partnerRole));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.empty());
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PartnerProfile result = promotionService.grantPartnerRoleAndProfile(userId, "Acme", "https://acme.com");

        assertThat(user.getRoles()).contains(partnerRole);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getCompanyName()).isEqualTo("Acme");
        verify(profileRepo).save(any());
    }

    @Test
    void grantPartnerRoleAndProfile_existingProfile_doesNotCreateDuplicate() {
        UUID userId = UUID.randomUUID();
        User user = makeUser(userId);
        Role partnerRole = new Role(RoleName.ROLE_PARTNER);
        PartnerProfile existing = new PartnerProfile();
        existing.setUserId(userId);
        existing.setCompanyName("Existing Co");

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(RoleName.ROLE_PARTNER)).thenReturn(Optional.of(partnerRole));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(existing));

        PartnerProfile result = promotionService.grantPartnerRoleAndProfile(userId, "New Name", "https://new.com");

        assertThat(result.getCompanyName()).isEqualTo("Existing Co"); // unchanged
        verify(profileRepo, never()).save(any());
    }

    @Test
    void grantPartnerRoleAndProfile_userNotFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> promotionService.grantPartnerRoleAndProfile(userId, "Acme", null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revokePartnerRole_removesRoleAndUnverifiesProfile() {
        UUID userId = UUID.randomUUID();
        Role partnerRole = new Role(RoleName.ROLE_PARTNER);
        User user = makeUser(userId);
        user.addRole(partnerRole);
        PartnerProfile profile = new PartnerProfile();
        profile.setUserId(userId);
        profile.setIsVerified(true);

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(RoleName.ROLE_PARTNER)).thenReturn(Optional.of(partnerRole));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        promotionService.revokePartnerRole(userId);

        assertThat(user.getRoles()).doesNotContain(partnerRole);
        assertThat(profile.getIsVerified()).isFalse();
        verify(userRepo).save(user);
        verify(profileRepo).save(profile);
    }

    @Test
    void revokePartnerRole_noProfile_stillRemovesRoleWithoutError() {
        UUID userId = UUID.randomUUID();
        Role partnerRole = new Role(RoleName.ROLE_PARTNER);
        User user = makeUser(userId);
        user.addRole(partnerRole);

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(RoleName.ROLE_PARTNER)).thenReturn(Optional.of(partnerRole));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.empty());

        promotionService.revokePartnerRole(userId);

        assertThat(user.getRoles()).doesNotContain(partnerRole);
        verify(profileRepo, never()).save(any());
    }

    @Test
    void revokePartnerRole_userNotFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> promotionService.revokePartnerRole(userId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
