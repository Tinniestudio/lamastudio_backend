# =========================
# 🏗️ Build Stage
# =========================
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests

# =========================
# 🚀 Runtime Stage
# =========================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# 🔒 Create non-root user
RUN useradd -m springuser

COPY --from=builder /app/target/*.jar app.jar

USER springuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]