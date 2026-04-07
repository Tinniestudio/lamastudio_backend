# 🎯 Authentication System Enhancement - Developer Guide

## 📚 Quick Navigation

| Document | Purpose |
|----------|---------|
| **IMPLEMENTATION_SUMMARY.md** | Complete feature overview & architecture |
| **API_EXAMPLES.md** | Curl examples & testing workflows |
| **DEPLOYMENT_CHECKLIST.md** | Production deployment guide |
| **This File** | Quick start & development reference |

---

## 🏗️ Architecture Overview

Three interconnected features were implemented:

### 1. **Redis-Based Rate Limiter** 🛡️
- **Purpose:** Prevent brute-force, spam, and abuse attacks
- **Technology:** Redis + Spring AOP
- **Usage:** `@RateLimit(maxRequests=5, windowMinutes=10)`
- **Strategy:** IP-based or User-based rate limit keys

### 2. **Resend Email Verification** 📧
- **Purpose:** Allow users to request new verification emails
- **Endpoint:** `POST /api/v1/auth/resend-verification-email`
- **Rate Limit:** 5 per 10 minutes (per user/IP)
- **Validation:** Prevents resending to already-verified emails

### 3. **Enhanced Email Verification** 🔐
- **Purpose:** Improved error handling + idempotency
- **Endpoint:** `GET /api/v1/auth/verify-email?token=xxx`
- **Features:** 
  - Clear error messages (expired vs invalid vs already verified)
  - Idempotent behavior (re-verifying returns success)
  - Actionable hints (resend if expired)

---

## 📁 Project Structure

```
src/main/java/com/lamastudio/backend/
├── config/
│   ├── ratelimit/                          [NEW]
│   │   ├── RateLimit.java                  - Annotation
│   │   ├── KeyStrategy.java                - Enum (USER_OR_IP, IP_ONLY, etc)
│   │   ├── RateLimiterService.java         - Interface
│   │   ├── RedisRateLimiterService.java    - Redis implementation
│   │   ├── RateLimitAspect.java            - AOP interceptor
│   │   └── RateLimitConfig.java            - Spring configuration
│   ├── RedisConfig.java                    [NEW]
│   └── SecurityConfig.java                 [UNCHANGED]
│
├── auth/
│   ├── controller/
│   │   └── AuthController.java
│   │       ├── resendVerificationEmail()   [NEW]
│   │       ├── verifyEmail()               [ENHANCED]
│   │       ├── login()                     [UPDATED - added @RateLimit]
│   │       └── forgotPassword()            [UPDATED - added @RateLimit]
│   │
│   ├── service/
│   │   └── AuthService.java
│   │       ├── resendVerificationEmail()   [NEW]
│   │       ├── verifyEmailEnhanced()       [NEW]
│   │       └── verifyEmail()               [UNCHANGED]
│   │
│   └── dto/
│       ├── ResendVerificationEmailRequest.java [NEW]
│       └── VerifyEmailResponse.java            [NEW]
│
└── exception/
    ├── RateLimitExceededException.java     [NEW]
    ├── InvalidEmailStateException.java     [NEW]
    ├── EmailTokenExpiredException.java     [NEW]
    └── GlobalExceptionHandler.java         [ENHANCED]

src/main/resources/
└── application.yml                         [ENHANCED]
    └── Added Redis configuration
```

---

## 🚀 Getting Started (Local Development)

### Prerequisites
```bash
# Java 21+
java -version

# Maven 3.8+
mvn -version

# PostgreSQL (existing)
psql --version

# Redis 7+ (new)
redis-cli --version
```

### Setup Steps

**1. Start Redis**
```bash
# Option A: Docker (recommended)
docker run -d -p 6379:6379 redis:7-alpine

# Option B: Local Redis
redis-server

# Option C: Docker Compose
docker-compose up -d redis
```

**2. Configure Environment**
```bash
# Copy example .env
cp .env.example .env

# Edit .env with your Redis host
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=  # Empty for local development
```

**3. Build & Run**
```bash
# Build
mvn clean compile

# Run
mvn spring-boot:run

# Verify
curl http://localhost:8080/swagger-ui.html
```

**4. Test the Features**
```bash
# See API_EXAMPLES.md for complete examples

# Quick test: Resend verification email
curl -X POST http://localhost:8080/api/v1/auth/resend-verification-email \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com"}'
```

---

## 🔍 Code Walkthrough

### Rate Limiting Flow

```
1. Controller Method receives @RateLimit annotation
   ↓
2. Spring AOP intercepts (RateLimitAspect.aroundRateLimit)
   ↓
3. Extract @RateLimit metadata (maxRequests, windowMinutes, keyStrategy)
   ↓
4. Determine rate limit key:
   - IF authenticated → use userId
   - ELSE → use client IP
   ↓
5. Call RedisRateLimiterService.checkAndIncrement()
   ↓
6. Redis INCR operation (atomic)
   - IF count > maxRequests → throw RateLimitExceededException (429)
   - ELSE → proceed with method
   ↓
7. GlobalExceptionHandler catches exception
   ↓
8. Return 429 response with Retry-After header
```

### Email Verification Flow

```
1. User receives verification email with token
   ↓
2. User clicks link → GET /auth/verify-email?token=xxx
   ↓
3. AuthService.verifyEmailEnhanced(token)
   - Find user by token
   - Check if already verified → return idempotent success
   - Check if token expired → return specific error with actionRequired hint
   - Mark email verified
   ↓
4. Return VerifyEmailResponse with:
   - message: "Email verified successfully"
   - alreadyVerified: false (or true if already verified)
   - timestamp
```

### Resend Verification Flow

```
1. User calls POST /auth/resend-verification-email
   ↓
2. Rate limiter checks (max 5/10 min)
   - IF limit exceeded → return 429
   ↓
3. AuthService.resendVerificationEmail(email)
   - Validate user exists
   - Check email NOT already verified
   - Check user is not OAuth2-only
   - Check account is active
   - Invalidate old token
   - Generate new token
   - Set new expiry (24 hours)
   - Save user
   ↓
4. EmailService.sendVerificationEmail()
   - Send new verification email with new token
   ↓
5. Return 200 OK
```

---

## 🧪 Testing

### Unit Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=RateLimitAspectTest

# Run with coverage
mvn test jacoco:report
```

### Integration Tests
```bash
# Create test user and verify flow
mvn test -Dtest=AuthIntegrationTest

# Test rate limiting with multiple requests
mvn test -Dtest=RateLimitIntegrationTest
```

### Manual Testing (Postman/Curl)
See `API_EXAMPLES.md` for comprehensive curl examples

### Redis Testing
```bash
# Monitor Redis activity
redis-cli MONITOR

# Check rate limit keys
redis-cli KEYS "ratelimit:*"

# See TTL of a key
redis-cli TTL "ratelimit:login:ip:127.0.0.1"

# Clear a specific key
redis-cli DEL "ratelimit:login:ip:127.0.0.1"

# Clear all rate limit keys
redis-cli KEYS "ratelimit:*" | xargs redis-cli DEL
```

---

## ⚙️ Configuration Reference

### @RateLimit Annotation Parameters

```java
@RateLimit(
    maxRequests = 5,           // Max requests allowed
    windowMinutes = 10,        // Time window in minutes
    keyStrategy = "USER_OR_IP" // USER_OR_IP, IP_ONLY, USER_ONLY, ENDPOINT_ONLY
)
public ResponseEntity<?> endpoint() { }
```

### Key Strategies Explained

| Strategy | Use Case | Example |
|----------|----------|---------|
| USER_OR_IP | Most endpoints | Resend email (5/user, global IP fallback) |
| IP_ONLY | Public endpoints | Login, forgot password (brute-force protection) |
| USER_ONLY | Authenticated | User quota (requires authentication) |
| ENDPOINT_ONLY | Global quota | API rate limiting across all users |

### Redis Configuration (application.yml)

```yaml
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    timeout: 2000ms
    jedis:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 2
```

---

## 🐛 Debugging Tips

### Enable Debug Logging
```properties
# application-dev.properties
logging.level.com.lamastudio.backend.config.ratelimit=DEBUG
logging.level.com.lamastudio.backend.auth=DEBUG
```

### Check Redis Connection
```java
// In any @Component
@Autowired
private RedisTemplate<String, Object> redisTemplate;

// Test connection
String result = redisTemplate.execute(c -> c.ping());
System.out.println("Redis ping: " + result); // Should print "PONG"
```

### Monitor Rate Limit Keys
```bash
# Watch rate limit activity
redis-cli MONITOR | grep ratelimit

# Check specific user rate limit
redis-cli GET "ratelimit:resend-verification-email:user:john-uuid"

# Check TTL
redis-cli TTL "ratelimit:resend-verification-email:user:john-uuid"
```

### Common Issues

| Problem | Solution |
|---------|----------|
| 429 Too Many Requests immediately | Previous requests' TTL not expired. Wait 1-30min or use new IP |
| Redis connection refused | Ensure Redis running: `redis-cli ping` should return PONG |
| Rate limiter not working | Check @EnableAspectJAutoProxy is active, AOP enabled |
| Email not sending | Check EmailService configuration, verify email templates |

---

## 📚 Key Classes Reference

### RateLimitAspect
```java
// Intercepts @RateLimit methods
// Builds rate limit key based on strategy
// Delegates to RateLimiterService
```

### RedisRateLimiterService
```java
// Redis INCR with TTL
// Sliding window algorithm
// Retry-after calculation
```

### AuthService
```java
// resendVerificationEmail(String email)
//   - Validates user state
//   - Generates new token
//   - Sends email

// verifyEmailEnhanced(String token)
//   - Returns VerifyEmailResponse
//   - Handles already-verified (idempotent)
//   - Specific error for expired tokens
```

### GlobalExceptionHandler
```java
// Maps 3 new exceptions:
// - RateLimitExceededException (429)
// - InvalidEmailStateException (400)
// - EmailTokenExpiredException (400)
```

---

## 🔒 Security Considerations

✅ **Rate Limiting:**
- Prevents brute-force attacks on login
- Prevents email enumeration on forgot-password
- Prevents spam on resend-verification
- IP-based for unauthenticated users

✅ **Token Safety:**
- Single-use tokens (invalidated after verification)
- 24-hour expiry for email tokens
- 1-hour expiry for reset tokens
- Tokens cleared from DB after use

✅ **Error Handling:**
- No user enumeration (404 for non-existent users)
- Clear, non-leaking error messages
- Specific errors guide user to correct action

---

## 🚀 Next Steps (Optional Enhancements)

1. **Metrics & Monitoring**
   - Add Prometheus metrics for rate limit violations
   - Dashboard for monitoring rate limit activity

2. **Advanced Rate Limiting**
   - Sliding window with finer granularity
   - Exponential backoff for repeated violations
   - Whitelist/blacklist support

3. **Redis Optimization**
   - Redis Cluster for HA
   - Redis Sentinel for failover
   - Connection pool optimization

4. **Email Improvements**
   - Template customization
   - Batch sending optimization
   - Bounce handling

---

## 📖 Further Reading

- Spring Security Documentation: https://spring.io/projects/spring-security
- Spring AOP Guide: https://spring.io/guides/gs/aspect-oriented/
- Redis Documentation: https://redis.io/documentation
- Jedis Documentation: https://github.com/redis/jedis

---

## 💡 Quick References

### Build Commands
```bash
mvn clean compile                    # Compile
mvn test                             # Run tests
mvn package -DskipTests              # Build JAR
mvn spring-boot:run                  # Run locally
mvn clean install                    # Full build
```

### Redis Commands
```bash
redis-cli ping                       # Test connection
redis-cli KEYS "pattern"             # List keys
redis-cli GET key                    # Get value
redis-cli TTL key                    # Check expiry
redis-cli DEL key                    # Delete key
redis-cli FLUSHDB                    # Clear all keys (CAUTION)
redis-cli MONITOR                    # Monitor operations
```

### Useful Endpoints
```
Swagger UI:      http://localhost:8080/swagger-ui.html
Health Check:    http://localhost:8080/api/v1/actuator/health
API Docs:        http://localhost:8080/api-docs
OpenAPI YAML:    http://localhost:8080/api-docs.yaml
```

---

**Version:** 1.0.0  
**Last Updated:** April 4, 2026  
**Status:** ✅ Production Ready

For questions, see IMPLEMENTATION_SUMMARY.md or contact the development team.
