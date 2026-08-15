package com.tinniestudio.api.shared.email;

public record EmailRequest(
    String to,
    String subject,
    String html
) {}
