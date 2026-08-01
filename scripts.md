API Service (api-service)
Run

# Start the application (default profile, port 8080)
./gradlew :api-service:bootRun

# Start with a specific Spring profile
./gradlew :api-service:bootRun --args='--spring.profiles.active=prod'

# Start using the test classpath (useful for integration testing)
./gradlew :api-service:bootTestRun
Test

# Run all tests
./gradlew :api-service:test

# Run a specific test class
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationServiceImplTest"

# Run tests matching a pattern
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.analytics.*"

# Force re-run (skip Gradle's UP-TO-DATE cache)
./gradlew :api-service:test --rerun

# Run with continuous mode (re-runs on file change)
./gradlew :api-service:test --continuous
Build

# Compile only (fast, no tests)
./gradlew :api-service:compileJava

# Compile test sources
./gradlew :api-service:compileTestJava

# Build executable JAR (includes tests)
./gradlew :api-service:build

# Build executable JAR only (skip tests)
./gradlew :api-service:bootJar

# Assemble without running tests
./gradlew :api-service:assemble

# Build Docker/OCI image (requires Docker daemon)
./gradlew :api-service:bootBuildImage
Clean

# Delete build directory
./gradlew :api-service:clean

# Clean then build
./gradlew :api-service:clean :api-service:build
Inspect

# List all declared dependencies
./gradlew :api-service:dependencies

# Show dependency tree for a specific config
./gradlew :api-service:dependencies --configuration runtimeClasspath

# Insight into one specific dependency
./gradlew :api-service:dependencyInsight --dependency shedlock

# Generate Javadoc
./gradlew :api-service:javadoc
Media Worker (media-worker)

# Run the worker
./gradlew :media-worker:bootRun

# Run with profile
./gradlew :media-worker:bootRun --args='--spring.profiles.active=prod'

# Run tests
./gradlew :media-worker:test

# Build JAR
./gradlew :media-worker:bootJar

# Build Docker image
./gradlew :media-worker:bootBuildImage

# Clean
./gradlew :media-worker:clean
Root (both modules together)

# Build everything
./gradlew build

# Test everything
./gradlew test

# Clean everything
./gradlew clean

# Clean + build all
./gradlew clean build

# Compile all
./gradlew compileJava

# List all subprojects
./gradlew projects
Useful Flags (work on any command)

# Skip tests during build
./gradlew :api-service:build -x test

# Show full stacktrace on failure
./gradlew :api-service:test --stacktrace

# Show info-level logging
./gradlew :api-service:bootRun --info

# Show all deprecation warnings
./gradlew :api-service:build --warning-mode all

# Run in parallel (faster for multi-module)
./gradlew build --parallel

# Offline mode (use cached deps only)
./gradlew :api-service:build --offline

# Refresh dependency cache
./gradlew :api-service:build --refresh-dependencies