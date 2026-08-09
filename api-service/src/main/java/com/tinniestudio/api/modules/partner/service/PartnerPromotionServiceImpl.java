package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.role.repository.RoleRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.entity.Role;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerPromotionServiceImpl implements PartnerPromotionService {

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final PartnerProfileRepository profileRepo;

    @Override
    @Transactional
    public PartnerProfile grantPartnerRoleAndProfile(UUID userId, String companyName, String websiteUrl) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        Role partnerRole = roleRepo.findByName(RoleName.ROLE_PARTNER)
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_PARTNER not found"));
        user.addRole(partnerRole);
        userRepo.save(user);

        return profileRepo.findByUserId(userId).orElseGet(() -> {
            PartnerProfile profile = new PartnerProfile();
            profile.setUserId(userId);
            profile.setCompanyName(companyName);
            profile.setWebsiteUrl(websiteUrl);
            return profileRepo.save(profile);
        });
    }
}
