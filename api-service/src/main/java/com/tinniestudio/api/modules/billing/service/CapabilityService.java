package com.tinniestudio.api.modules.billing.service;

import java.util.UUID;

public interface CapabilityService {

    boolean canWatch(UUID userId);

    void recordWatch(UUID userId);
}
