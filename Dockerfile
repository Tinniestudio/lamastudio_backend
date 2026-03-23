# =========================
# 🏗️ Build Stage
# =========================
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# 👇 Use host network for Maven (IMPORTANT)
RUN apt-get update && apt-get install -y curl

COPY pom.xml .

RUN mvn -B -Djava.net.preferIPv4Stack=true dependency:go-offline

COPY src ./src

RUN mvn clean package -DskipTests -B


# =========================
# 🚀 Runtime Stage
# =========================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]