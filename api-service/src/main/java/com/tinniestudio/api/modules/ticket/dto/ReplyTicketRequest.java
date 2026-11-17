package com.tinniestudio.api.modules.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class ReplyTicketRequest {

    @NotBlank(message = "Message is required")
    private String body;

    private String attachmentStorageKey;
}
