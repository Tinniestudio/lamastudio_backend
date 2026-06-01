package com.lamastudio.backend.modules.auth.admin.dto;

import com.lamastudio.backend.modules.auth.admin.entity.AdminRoleName;
import lombok.Builder;
import lombok.Getter;

import java.util.Set;
import java.util.UUID;

@Getter
@Builder
public class AdminAuthResponse {
    private UUID adminId;
    private String email;
    private String firstName;
    private String lastName;
    private Set<AdminRoleName> roles;
    private String message;
}
