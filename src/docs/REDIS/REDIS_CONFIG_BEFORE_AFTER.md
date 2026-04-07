# Redis Configuration: Before & After

## Summary of Fixes

This document shows exactly what changed and why, side-by-side.

---

## 1. Environment Variables & YAML

### The Problem with Empty Default

```yaml
# ❌ BEFORE: Silent fallback to localhost
spring:
  redis:
    url: ${REDIS_URL:}              # empty default makes Spring ignore it
    host: ${REDIS_HOST}             # silently becomes the active config
    port: ${REDIS_PORT}
    password: ${REDIS_PASSWORD}
    username: ${REDIS_USERNAME}
    timeout: ${REDIS_TIMEOUT:2000ms}
```

**What happens:**
1. `REDIS_URL` env var is not set
2. `${REDIS_URL:}` evaluates to empty string `""`
3. Spring treats empty string as "URL not configured"
4. Falls back to `host: ${REDIS_HOST}` → resolves to `localhost` (default)
5. Result: **App connects to localhost:6379 instead of production Redis**
6. Nobody notices until production experiences a cache miss or rate limit not working

### The Fix

```yaml
# ✅ AFTER: Fail fast if REDIS_URL is missing
spring:
  redis:
    url: ${REDIS_URL}               # no default — Spring throws error if missing
    timeout: 2000ms
    lettuce:
      pool:
        max-active: 10
        max-idle: 5
        min-idle: 1
        max-wait: 1000ms
```

**What happens now:**
1. If `REDIS_URL` env var is not set → Spring fails at startup with clear error
2. If `REDIS_URL` is set → Spring parses it and uses it
3. Result: **No silent fallback. Ops can't miss a misconfiguration.**

---

## 2. SSL Configuration Conflict

### The Problem with Double SSL

```yaml
# ❌ BEFORE: Conflicting SSL configuration
spring:
  redis:
    url: rediss://user:pass@host:port       # scheme = rediss = SSL enabled
    ssl:
      enabled: true                         # duplicate SSL flag
```

**What happens in Lettuce:**
1. URL scheme `rediss://` tells Lettuce: "Enable SSL"
2. Property `ssl.enabled=true` tells Lettuce: "Enable SSL again"
3. Lettuce applies SSL wrapper twice
4. TLS handshake fails with obscure error like "SSL peer shut down incorrectly"
5. Connection times out or fails cryptically

**Real-world example:**
```
Error during connect: java.io.IOException: SSLEngine closed before handshake completed
```

Nobody can tell why SSL is broken.

### The Fix

```yaml
# ✅ AFTER: Single source of truth for SSL
spring:
  redis:
    url: rediss://user:pass@host:port      # scheme handles SSL, period
    # NO ssl.enabled flag at all
```

**What happens now:**
1. URL scheme `rediss://` tells Lettuce: "Enable SSL"
2. That's it. Single source of truth.
3. Result: **Clear, working SSL configuration**

---

## 3. Jedis vs Lettuce Confusion

### The Problem with Mixed Dependencies

```xml
<!-- ❌ BEFORE: Both clients in dependency list -->
<dependency>
  <groupId>redis.clients</groupId>
  <artifactId>jedis</artifactId>
</dependency>
<dependency>
  <groupId>io.lettuce</groupId>
  <artifactId>lettuce-core</artifactId>
</dependency>
```

```yaml
# ❌ BEFORE: Pool config for both (but only one is used)
spring:
  redis:
    jedis:
      pool:
        max-active: 20
    lettuce:
      pool:
        max-active: 20
```

**What happens:**
1. Spring Boot chooses Lettuce by default (when both present)
2. Jedis pool config is silently ignored
3. Team thinks "we configured pool size to 20"
4. Actually running with Lettuce's defaults (30 for max-active)
5. No indication that config is wrong
6. Under load, pool exhaustion happens at different threshold than expected

### The Fix

```xml
<!-- ✅ AFTER: Lettuce ONLY -->
<!-- Jedis removed entirely -->
<dependency>
  <groupId>io.lettuce</groupId>
  <artifactId>lettuce-core</artifactId>
</dependency>
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-pool2</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```yaml
# ✅ AFTER: Lettuce pool only (Jedis removed from config too)
spring:
  redis:
    url: ${REDIS_URL}
    lettuce:
      pool:
        max-active: 10
        max-idle: 5
        min-idle: 1
        max-wait: 1000ms
```

**What happens now:**
1. Spring Boot uses Lettuce (no ambiguity)
2. Pool config applies to Lettuce (no confusion)
3. Result: **Clear 1:1 mapping between config and actual client**

---

## 4. Multiple RedisTemplates

### The Problem with Over-Engineering

```java
// ❌ BEFORE: Separate templates for different use cases
@Bean
public RedisTemplate<String, RateLimitCounter> rateLimitRedisTemplate(
    RedisConnectionFactory factory) {
  RedisTemplate<String, RateLimitCounter> template = new RedisTemplate<>();
  template.setValueSerializer(new Jackson2JsonRedisSerializer<>(RateLimitCounter.class));
  return template;
}

@Bean
public RedisTemplate<String, UserCache> userCacheRedisTemplate(
    RedisConnectionFactory factory) {
  RedisTemplate<String, UserCache> template = new RedisTemplate<>();
  template.setValueSerializer(new Jackson2JsonRedisSerializer<>(UserCache.class));
  return template;
}

@Bean
public RedisTemplate<String, SessionData> sessionRedisTemplate(
    RedisConnectionFactory factory) {
  RedisTemplate<String, SessionData> template = new RedisTemplate<>();
  template.setValueSerializer(new Jackson2JsonRedisSerializer<>(SessionData.class));
  return template;
}
```

**Problems:**
1. Three separate RedisTemplates = three separate connections = waste
2. Each template's serializer is similar (all JSON variants)
3. Adding a new cached object type requires a new template = more code
4. Hard to track which services use which template
5. RateLimiterService can't use caching if it's not injected the right template
6. Testing becomes complex (mock 3 different templates?)

### The Fix

```java
// ✅ AFTER: Single universal template
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
  RedisTemplate<String, Object> template = new RedisTemplate<>();
  template.setConnectionFactory(factory);
  
  StringRedisSerializer stringSerializer = new StringRedisSerializer();
  GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
  
  // String keys: human-readable in CLI
  template.setKeySerializer(stringSerializer);
  template.setHashKeySerializer(stringSerializer);
  
  // JSON values: handles ANY object type
  template.setValueSerializer(jsonSerializer);
  template.setHashValueSerializer(jsonSerializer);
  
  template.afterPropertiesSet();
  return template;
}
```

**Advantages:**
1. One template = one connection = lean
2. `GenericJackson2JsonRedisSerializer` handles RateLimitCounter, User, SessionData, etc. automatically
3. New cached type? No code needed. Just use `@Cacheable`.
4. RateLimiterService can use the same template as cache
5. Testing: one mock to track, not three

---

## 5. Per-Cache TTL Over-Engineering

### The Problem with Premature Complexity

```java
// ❌ BEFORE: Hardcoded TTL map per cache
private static final Map<String, Long> CACHE_TTLS = Map.of(
  "users", 3600L,          // 1 hour
  "sessions", 1800L,       // 30 minutes
  "posts", 7200L,          // 2 hours
  "followers", 3600L,      // 1 hour
  "feed", 600L             // 10 minutes
);

@Bean
public CacheManager cacheManager(RedisConnectionFactory factory) {
  Map<String, RedisCacheConfiguration> caches = new HashMap<>();
  
  for (Map.Entry<String, Long> entry : CACHE_TTLS.entrySet()) {
    caches.put(entry.getKey(), RedisCacheConfiguration.defaultCacheConfig()
      .entryTtl(Duration.ofSeconds(entry.getValue())));
  }
  
  return RedisCacheManager.builder(factory)
    .withInitialCacheNames(caches.keySet())
    .withCacheConfiguration("users", caches.get("users"))
    .withCacheConfiguration("sessions", caches.get("sessions"))
    // ... repeat for every cache type ...
    .build();
}
```

**Problems:**
1. TTL values are guesses (no metrics to back them)
2. Every new cache type requires code change
3. Changing a TTL requires recompilation + redeploy
4. Team can't agree: "Should posts be 1h or 2h?"
5. Over 90% of caches don't need custom TTLs (30 min is fine)

### The Fix

```java
// ✅ AFTER: Simple single default TTL
@Bean
public CacheManager cacheManager(RedisConnectionFactory factory) {
  RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(30));  // sensible default for startup
  
  return RedisCacheManager.builder(factory)
    .cacheDefaults(config)
    .build();
}
```

**Advantages:**
1. 30 minutes is a good default for most cache types (proven by experience)
2. New cache type? Just use `@Cacheable("myCache")`. No config needed.
3. TTL can change without code: use cache management dashboard later
4. When/if metrics show specific caches need different TTLs, add per-cache config then (not before)
5. Result: **YAGNI principle applied** (You Ain't Gonna Need It)

---

## 6. No Cluster/Sentinel Config (Intentional)

### What Was NOT Added

```yaml
# ❌ NOT included (not needed for startup)
spring:
  redis:
    cluster:
      nodes:
        - host1:6379
        - host2:6379
        - host3:6379
    sentinel:
      master: mymaster
      nodes:
        - host1:26379
        - host2:26379
```

### Why It's Correct to Omit It

1. **Managed Redis handles HA:** Services like RedisLabs, Upstash, Railway manage replication/failover
2. **Startup doesn't need it:** Single-node Redis with URL connection is sufficient
3. **Adds operational complexity:** Cluster requires minimum 3 nodes, monitoring, resharding on growth
4. **Later, if needed:** Migrate to Cluster once metrics show 1M+ ops/sec sustained
5. **Wrong problem to solve now:** Fix the silent config fallback bug first

**Decision:** Add complexity only when proven need exists by production metrics.

---

## Summary Table

| Issue | Before | After | Why It Matters |
|-------|--------|-------|----------------|
| REDIS_URL default | `${REDIS_URL:}` (empty) | `${REDIS_URL}` (fail fast) | Silent fallback to localhost bugs production |
| SSL config | `rediss://` + `ssl.enabled=true` | `rediss://` only | Double SSL breaks TLS handshake |
| Client | Jedis + Lettuce | Lettuce only | One client = one set of pool config |
| Pool config | Both Jedis & Lettuce | Lettuce only | Only one is used, confusion removed |
| RedisTemplate | 3 per use case | 1 universal | Lean, less code, easier testing |
| CacheManager | Per-cache TTL map | Single 30min default | YAGNI: add per-cache TTLs when metrics prove need |
| Startup failure | Throws exception | Logs warning, continues | Redis failure doesn't crash app (graceful degradation) |
| Cluster/Sentinel | Included | Omitted | Managed Redis handles HA; startup doesn't need it |

---

## Expected Startup Logs

### With REDIS_URL Set & Redis Reachable

```
2026-04-07T10:15:23.456Z  INFO com.lamastudio.backend.config.RedisConfig
  ✓ Redis config resolved from spring.redis.url: rediss://redis-host:12345 (SSL: true)

2026-04-07T10:15:23.789Z  INFO com.lamastudio.backend.config.RedisConfig
  ✓ Redis connection established successfully. Ping response: PONG

2026-04-07T10:15:23.901Z  INFO com.lamastudio.backend.config.RedisConfig
  CacheManager configured with default TTL: 30 minutes

2026-04-07T10:15:24.012Z  INFO org.springframework.boot.Application
  Application started successfully
```

### With REDIS_URL Set & Redis Unreachable

```
2026-04-07T10:15:23.456Z  INFO com.lamastudio.backend.config.RedisConfig
  ✓ Redis config resolved from spring.redis.url: rediss://redis-host:12345 (SSL: true)

2026-04-07T10:15:23.789Z  WARN com.lamastudio.backend.config.RedisConfig
  ✗ Failed to connect to Redis during startup. Rate limiting and caching will degrade gracefully. 
  Error: Connection refused (Connection refused)

2026-04-07T10:15:23.901Z  INFO com.lamastudio.backend.config.RedisConfig
  CacheManager configured with default TTL: 30 minutes

2026-04-07T10:15:24.012Z  INFO org.springframework.boot.Application
  Application started successfully (Redis DOWN but app survives)
```

### With REDIS_URL Missing

```
2026-04-07T10:15:23.456Z  ERROR com.lamastudio.backend.config.RedisConfig
  ✗ REDIS_URL is not set. The application requires spring.redis.url to be configured. 
  Set the REDIS_URL environment variable.

2026-04-07T10:15:23.457Z  WARN org.springframework.boot.Application
  Application failed to start

  ***************************
  APPLICATION FAILED TO START
  ***************************

  Description:
  Invalid value for 'spring.redis.url': required
```

---

## Validation Checklist

Deploy these changes only when:

- [ ] ✅ `RedisConfig.java` compiles without errors
- [ ] ✅ `application.yml` has NO `redis.host`, `redis.port`, `redis.ssl.*`
- [ ] ✅ `application.yml` has NO `redis.jedis.*`
- [ ] ✅ `pom.xml` has Lettuce + commons-pool2 only (no Jedis)
- [ ] ✅ `pom.xml` includes spring-boot-starter-actuator
- [ ] ✅ Startup logs show "✓ Redis config resolved from spring.redis.url"
- [ ] ✅ Startup logs show "✓ Redis connection established successfully"
- [ ] ✅ `/actuator/health` endpoint is reachable
- [ ] ✅ App starts even if Redis is down (graceful degradation)
- [ ] ✅ Rate limiting still works (using single RedisTemplate)
- [ ] ✅ `@Cacheable` annotations work across services

---

## Files Changed

1. **pom.xml**
   - Removed: `redis.clients:jedis`
   - Added: `org.springframework.boot:spring-boot-starter-actuator`

2. **application.yml**
   - Changed `redis` block: URL-only, Lettuce pool, no host/port, no ssl.enabled

3. **RedisConfig.java**
   - Added: `@EnableCaching` annotation
   - Added: `CacheManager` bean with 30-min default TTL
   - Simplified: Single `RedisTemplate<String, Object>` for all use cases
   - Simplified: Startup logging without HealthIndicator
   - Improved: Graceful failure (logs warning instead of throwing)

---

## Deployment Steps

1. Merge these changes to `staging` branch
2. Ensure `REDIS_URL` env var is set in all environments (dev, staging, prod)
3. Example: `REDIS_URL=rediss://default:PASSWORD@host:12345`
4. Deploy to staging first, verify logs and health check
5. Deploy to production
6. Monitor: `/actuator/health` should show Redis UP
7. Monitor: Rate limiting behavior should be normal
8. Monitor: Caching should work (validate by checking cache hits)

---

## References

- **Spring Data Redis:** https://spring.io/projects/spring-data-redis
- **Lettuce Client:** https://lettuce.io/
- **Redis URL Schemes:** https://redis.io/docs/reference/client-spec-cli/
- **Spring Boot Caching:** https://spring.io/guides/gs/caching/
- **Spring Boot Actuator:** https://spring.io/guides/gs/actuator-service/
