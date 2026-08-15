# Build JAR locally first: ./gradlew :media-worker:bootJar -x test
# This host blocks GitHub and Ubuntu apt mirrors, so we avoid both:
#   - Gradle build is done locally and JAR is copied in
#   - FFmpeg is pulled from Docker Hub (mwader/static-ffmpeg) as static binaries

FROM mwader/static-ffmpeg:latest AS ffmpeg

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Static binaries have zero shared-lib dependencies — works on any Linux image
COPY --from=ffmpeg /ffmpeg  /usr/local/bin/ffmpeg
COPY --from=ffmpeg /ffprobe /usr/local/bin/ffprobe

RUN useradd -m workeruser

COPY media-worker/build/libs/tinniestudio-media-worker-*.jar app.jar

RUN mkdir -p /tmp/tinniestudio && chown workeruser /tmp/tinniestudio

USER workeruser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
