package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class UpdateTicketStatusRequest {

    @NotNull(message = "Status is required")
    private TicketStatus status;
}
