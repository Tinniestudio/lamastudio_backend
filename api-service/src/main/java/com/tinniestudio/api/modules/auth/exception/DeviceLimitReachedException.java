package com.tinniestudio.api.modules.auth.exception;

import com.tinniestudio.api.modules.auth.user.dto.SessionDto;

import java.util.List;

/**
 * Thrown by AuthService.login() when the user is already at their subscription's device cap.
 * Carries everything the frontend's device-picker modal needs: how many devices are allowed,
 * the current list to choose from, and a single-use token that proves — without re-sending the
 * password — that whichever device gets picked next really was chosen by the same login attempt.
 */
public class DeviceLimitReachedException extends RuntimeException {
    private final int maxDevices;
    private final String resolutionToken;
    private final List<SessionDto> devices;

    public DeviceLimitReachedException(String message, int maxDevices, String resolutionToken, List<SessionDto> devices) {
        super(message);
        this.maxDevices = maxDevices;
        this.resolutionToken = resolutionToken;
        this.devices = devices;
    }

    public int getMaxDevices() {
        return maxDevices;
    }

    public String getResolutionToken() {
        return resolutionToken;
    }

    public List<SessionDto> getDevices() {
        return devices;
    }
}
