package com.lamastudio.backend.shared.email;

public record EmailRequest(
    String to,
    String subject,
    String html
) {}
