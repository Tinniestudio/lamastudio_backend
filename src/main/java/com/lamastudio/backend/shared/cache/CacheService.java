package com.lamastudio.backend.shared.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {

    void set(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void delete(String key);

    boolean exists(String key);
}
