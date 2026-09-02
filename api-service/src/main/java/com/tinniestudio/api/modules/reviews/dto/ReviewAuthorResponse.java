package com.tinniestudio.api.modules.reviews.dto;

import com.tinniestudio.api.shared.entity.User;

public record ReviewAuthorResponse(String displayName, String avatarUrl) {
    /**
     * Falls back displayName -> firstName -> a generic label, never to email (a review list is
     * semi-public; email would be a privacy leak the fallback in other parts of the app that use
     * email as a last resort — e.g. UserProfileServiceImpl's notification-greeting fallback — is
     * not appropriate to copy here).
     */
    public static ReviewAuthorResponse from(User u) {
        String name = u.getDisplayName() != null ? u.getDisplayName()
            : u.getFirstName() != null ? u.getFirstName()
            : "Member";
        return new ReviewAuthorResponse(name, u.getAvatarUrl());
    }
}
