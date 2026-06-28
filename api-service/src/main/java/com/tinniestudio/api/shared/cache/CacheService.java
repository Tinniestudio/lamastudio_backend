package com.tinniestudio.api.shared.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {

    void set(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void delete(String key);

    boolean exists(String key);

    /** Atomic set-if-absent (NX). Returns true if the key was set, false if it already existed. */
    boolean setIfAbsent(String key, String value, Duration ttl);
}
