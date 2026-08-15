package com.tinniestudio.api.modules.admin.dto;

import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class UpdateUserRequest {
    @Email
    private String email;
    private Boolean emailVerified;
}
