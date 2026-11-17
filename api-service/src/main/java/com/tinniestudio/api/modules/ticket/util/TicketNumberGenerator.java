package com.tinniestudio.api.modules.ticket.util;

import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates a short, human-friendly ticket reference (e.g. "TKT-7F3K9Q") shown to the user as
 * their confirmation number. Cosmetic only — never used for internal lookups, which always go
 * through the ticket's real UUID id. The alphabet deliberately excludes 0/O/1/I to avoid
 * ambiguity when a user reads the code aloud over the phone or re-types it from an email.
 */
@Component
@RequiredArgsConstructor
public class TicketNumberGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private static final int MAX_ATTEMPTS = 10;

    private final SupportTicketRepository ticketRepository;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = "TKT-" + randomSuffix();
            if (!ticketRepository.existsByTicketNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique ticket number after " + MAX_ATTEMPTS + " attempts");
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
