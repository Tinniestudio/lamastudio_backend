package com.lamastudio.backend.email;

public record EmailRequest(
    String to,
    String subject,
    String html
) {}
