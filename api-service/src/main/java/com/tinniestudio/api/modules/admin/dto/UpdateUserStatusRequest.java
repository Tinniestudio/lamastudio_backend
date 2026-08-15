package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class UpdateUserStatusRequest {
    @NotNull
    private AccountStatus status;
    private String reason;
}
