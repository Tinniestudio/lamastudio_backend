# Redis Architecture Diagram

## Configuration Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    APPLICATION STARTUP                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Spring Boot reads application.yml                              │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ spring.redis.url: ${REDIS_URL}  (env var required)          │ │
│  │ spring.redis.timeout: 2000ms                                │ │
│  │ spring.redis.lettuce.pool.max-active: 10                    │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Lettuce ClientResources parses REDIS_URL                       │
│                                                                  │
│  Example URL: rediss://default:PASSWORD@redis-host:12345       │
│                                                                  │
│  ┌──────────────┬─────────────┬───────────┬────────────────┐   │
│  │ Scheme       │ Host        │ Port      │ SSL            │   │
│  ├──────────────┼─────────────┼───────────┼────────────────┤   │
│  │ rediss://    │ redis-host  │ 12345     │ ✓ Enabled      │   │
│  └──────────────┴─────────────┴───────────┴────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  LettuceConnectionFactory creates connection pool               │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Connection Pool (commons-pool2)                           │ │
│  │                                                            │ │
│  │  max-active: 10    → max 10 concurrent connections        │ │
│  │  max-idle: 5       → keep up to 5 idle connections        │ │
│  │  min-idle: 1       → maintain at least 1 connection       │ │
│  │  max-wait: 1000ms  → timeout if pool exhausted            │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
            ┌─────────────────┼─────────────────┐
            │                 │                 │
            ▼                 ▼                 ▼
    ┌──────────────────┐ ┌─────────────┐ ┌────────────────┐
    │ RedisTemplate    │ │ CacheManager│ │ Health Check   │
    │                  │ │             │ │                │
    │ Used by:         │ │ 30-min TTL  │ │ /actuator/     │
    │ - Rate Limiter   │ │             │ │ health/redis   │
    │ - Caching        │ │ @Cacheable  │ │                │
    └──────────────────┘ └─────────────┘ └────────────────┘
```

---

## Request Flow with Caching

```
┌──────────────────────────┐
│  HTTP Request            │
│  GET /api/v1/users/123   │
└──────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────┐
│  @Cacheable("users", key="#id")          │
│  public User getUserById(Long id) { }    │
└──────────────────────────────────────────┘
         │
         ▼
    ┌────────────────────┐
    │ Is in cache?       │
    └────────────────────┘
         │      │
         │      ├─ YES (cache hit) ──┐
         │                           │
         │      NO (cache miss)      │
         │      ┌──────────────────┐ │
         └─────►│ Query Database   │ │
                │ (JPA/SQL)        │ │
                └──────────────────┘ │
                         │           │
                         ▼           │
                ┌──────────────────┐ │
                │ Store in Redis   │ │
                │ (30 min TTL)     │ │
                └──────────────────┘ │
                         │           │
         ┌───────────────┴───────────┘
         │
         ▼
┌──────────────────────────┐
│  Return User Object      │
│  HTTP 200 OK             │
└──────────────────────────┘
```

---

## Rate Limiting Request Flow

```
┌──────────────────────────┐
│  HTTP Request            │
│  POST /api/v1/auth/login │
└──────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────┐
│  @RateLimit(limit=10, window=60)         │
│  public ResponseEntity login() { }       │
└──────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────┐
│  RateLimitAspect intercepts method call  │
└──────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────┐
│  RedisRateLimiterService.allowRequest()  │
│                                          │
│  Key: "rate-limit:user-123"              │
└──────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────┐
│  redisTemplate.opsForValue()             │
│  GET "rate-limit:user-123"               │
│                                          │
│  ┌──────────────────────────────────────┐│
│  │ Single RedisTemplate<String,Object>  ││
│  │ - Key Serializer: String             ││
│  │ - Value Serializer: JSON             ││
│  └──────────────────────────────────────┘│
└──────────────────────────────────────────┘
         │
         ▼
    ┌────────────────────┐
    │ Counter exists?    │
    └────────────────────┘
         │      │
         │      ├─ NO ──────────┐
         │                      │
         │      YES             │
         │      ┌──────────────┐│
         ├─────►│ Increment    ││
         │      │ counter      ││
         │      └──────────────┘│
         │              │        │
         │      ┌───────▼───────┐│
         │      │ Reached limit?││
         │      └───────────────┘│
         │         │       │     │
         │         │    YES├─────┼──┐
         │         │       │     │  │
         │      NO └───────┘     │  ▼
         │              │        │ ┌─────────────────┐
         │              ▼        │ │ HTTP 429        │
         │         ┌──────────┐  │ │ Too Many Reqs   │
         │         │Allow req │  │ └─────────────────┘
         │         └──────────┘  │
         └──────────────┬─────────┘
                        │
                        ▼
            ┌──────────────────────┐
            │ Continue with method │
            │ or reject request    │
            └──────────────────────┘
```

---

## Redis Connection Lifecycle

```
Application Start
       │
       ▼
┌─────────────────────────────────┐
│ LettuceConnectionFactory         │
│ - Parses spring.redis.url        │
│ - Configures ClientOptions       │
│ - Creates connection pool         │
└─────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────┐
│ ApplicationRunner bean           │
│ - Logs resolved config           │
│ - Tests connection (PING)        │
│ - Non-fatal (doesn't throw)      │
└─────────────────────────────────┘
       │
       ├─ SUCCESS ──────┐
       │                │
       │ FAILURE        ▼
       │           ┌───────────┐
       │           │ Log warn  │
       │           │ Graceful  │
       │           │ degrade   │
       │           └───────────┘
       │                │
       └────────┬───────┘
                │
                ▼
    ┌─────────────────────────┐
    │ Application Ready       │
    │ - Rate limiting works   │
    │ - Caching works         │
    │ - Or degrades gracefully│
    └─────────────────────────┘

During Runtime:

    ┌─────────────────────────┐
    │ Requests use pool       │
    │ ┌─────────────────────┐ │
    │ │ Active: 1-10        │ │
    │ │ Idle: 0-5           │ │
    │ │ Waiting: 0          │ │
    │ └─────────────────────┘ │
    └─────────────────────────┘

    ┌─────────────────────────┐
    │ /actuator/health checks │
    │ ┌─────────────────────┐ │
    │ │ Redis: UP           │ │
    │ │ Status: Responding  │ │
    │ └─────────────────────┘ │
    └─────────────────────────┘

On Shutdown:

    ┌─────────────────────────┐
    │ Gracefully close        │
    │ - Drain active conns    │
    │ - Close pool            │
    │ - Release resources     │
    └─────────────────────────┘
```

---

## Error Recovery Flow

```
           Redis Failure Detected
                    │
                    ▼
        ┌───────────────────────┐
        │ Where is Redis used?  │
        └───────────────────────┘
         │           │           │
         ▼           ▼           ▼

    CACHING     RATE LIMITING    SESSIONS

      │              │              │
      ▼              ▼              ▼

  No cache:      No limit:       Use in-memory
  Every call     Allow all       or degrade
  hits DB        requests


      ┌──────────────────────────┐
      │ Application Continues    │
      │ - Slower (no cache)      │
      │ - No rate limiting       │
      │ - But functional         │
      └──────────────────────────┘
             │
             ▼
      ┌──────────────────────────┐
      │ /actuator/health shows   │
      │ Redis: DOWN              │
      │ Status: Check logs       │
      └──────────────────────────┘
             │
             ▼
      ┌──────────────────────────┐
      │ Ops team alerted         │
      │ Fixes Redis or restarts  │
      └──────────────────────────┘
             │
             ▼
      ┌──────────────────────────┐
      │ Next request connects    │
      │ Services restore         │
      │ Normal operation         │
      └──────────────────────────┘
```

---

## Serialization Flow

### Key Serialization
```
Java Object (String):           "rate-limit:user-123"
              │
              ▼
StringRedisSerializer
              │
              ▼
Redis Protocol:                 "$18\r\nrate-limit:user-123\r\n"
              │
              ▼
Redis Wire:                     Sent as-is
              │
              ▼
Redis Storage:                  "rate-limit:user-123" (readable in CLI)
```

### Value Serialization
```
Java Object:                    RateLimitCounter { count: 5 }
              │
              ▼
GenericJackson2JsonRedisSerializer
              │
              ▼
JSON:                           {"count":5,"window":60}
              │
              ▼
Redis Protocol:                 "$23\r\n{"count":5,"window":60}\r\n"
              │
              ▼
Redis Wire:                     Sent as binary
              │
              ▼
Redis Storage:                  Binary JSON blob (not readable in CLI, but queryable)
```

---

## Configuration Validation

```
Application Startup

       │
       ▼
┌─────────────────────────────────┐
│ Is REDIS_URL env var set?       │
└─────────────────────────────────┘
       │         │
    YES│         │ NO
       │         └────────────────────┐
       │                              │
       │                              ▼
       │                    ┌──────────────────────┐
       │                    │ ERROR LOG            │
       │                    │ "✗ REDIS_URL not    │
       │                    │   configured"       │
       │                    └──────────────────────┘
       │                              │
       │                              ▼
       │                    Startup FAILS
       │
       ▼
┌─────────────────────────────────┐
│ Parse REDIS_URL                 │
│ Example: rediss://host:port     │
└─────────────────────────────────┘
       │
       ├─ Valid ──┐
       │          │
       │  Invalid │
       │          ▼
       │    ┌─────────────────────┐
       │    │ ERROR LOG           │
       │    │ "✗ Invalid URL      │
       │    │   format"           │
       │    └─────────────────────┘
       │          │
       │          ▼
       │    Startup FAILS
       │
       ▼
┌─────────────────────────────────┐
│ Check SSL scheme                │
│ rediss:// = SSL enabled         │
│ redis://  = No SSL              │
└─────────────────────────────────┘
       │
       ├─ rediss:// ──┐
       │              │
       │ redis://     ▼
       │     ┌────────────────────┐
       │     │ WARN LOG           │
       │     │ "⚠ No SSL, but     │
       │     │  may be OK locally"│
       │     └────────────────────┘
       │
       ▼
┌─────────────────────────────────┐
│ Test connection (PING)          │
└─────────────────────────────────┘
       │         │
    UP │         │ DOWN
       │         └────────────────────┐
       │                              │
       ▼                              ▼
┌─────────────────┐       ┌───────────────────────┐
│ SUCCESS LOG     │       │ WARN LOG              │
│ "✓ Redis        │       │ "✗ Redis unreachable │
│  connection OK" │       │  Graceful degrade"   │
└─────────────────┘       └───────────────────────┘
       │                              │
       └──────────────┬───────────────┘
                      │
                      ▼
        ┌─────────────────────────┐
        │ Enable CacheManager     │
        │ 30-min TTL              │
        └─────────────────────────┘
                      │
                      ▼
        ┌─────────────────────────┐
        │ Application READY       │
        │ ✓ Fully operational OR  │
        │ ✓ Degraded gracefully   │
        └─────────────────────────┘
```

---

## Health Check Response

```
GET /actuator/health

Response (Redis UP):
{
  "status": "UP",
  "components": {
    "redis": {
      "status": "UP",
      "details": {
        "redis": "OK"
      }
    },
    ... other components ...
  }
}

Response (Redis DOWN):
{
  "status": "UP",  ← App still UP!
  "components": {
    "redis": {
      "status": "DOWN",
      "details": {
        "redis": "UNREACHABLE",
        "error": "Connection refused..."
      }
    },
    ... other components ...
  }
}
```

---

## Dependency Injection Graph

```
┌──────────────────────────────────────────────────────┐
│  Spring ApplicationContext                           │
└──────────────────────────────────────────────────────┘

   ┌────────────────────────┐
   │ Environment            │
   │ (reads REDIS_URL var)  │
   └────────────┬───────────┘
                │
                ▼
   ┌────────────────────────────────┐
   │ LettuceConnectionFactory       │
   │ (creates connection pool)      │
   └────┬─────────────────────┬─────┘
        │                     │
        ▼                     ▼
  ┌──────────────┐    ┌──────────────────────┐
  │ RedisTemplate│    │ CacheManager         │
  │              │    │ (enables @Cacheable) │
  └──┬───────────┘    └──────────┬───────────┘
     │                           │
     │    ┌──────────────────────┘
     │    │
     │    ▼
     │ ┌────────────────────────────┐
     │ │ ApplicationRunner          │
     │ │ (startup check)            │
     │ └────────────────────────────┘
     │
     ├──► RateLimiterService
     │    (injected, uses template)
     │
     ├──► UserService
     │    (@Cacheable annotations)
     │
     └──► Other services
          (can use cache/redis)
```

---

## Environment Variable Resolution

```
OS/Container Sets:
  export REDIS_URL="rediss://default:password@host:port"

         │
         ▼

Spring Boot reads from Environment:
  environment.getProperty("spring.redis.url")

         │
         ▼

RedisProperties bean populated:
  RedisProperties {
    url: "rediss://default:password@host:port",
    timeout: 2000ms,
    lettuce: {
      pool: {
        max-active: 10,
        ...
      }
    }
  }

         │
         ▼

LettuceConnectionFactory created:
  - Parses URL
  - Creates pool
  - Ready for requests

         │
         ▼

Beans use factory:
  - RedisTemplate
  - CacheManager
  - Health indicator
```

---

This architecture provides:
- ✅ Single source of truth (URL drives everything)
- ✅ Fail-fast configuration (no empty defaults)
- ✅ Clear separation of concerns (template, cache, health)
- ✅ Graceful degradation (app survives Redis failures)
- ✅ Observable startup (logs show what's configured)
