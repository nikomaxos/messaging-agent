# ─── Build Stage ─────────────────────────────────────────────────────────────
# Use the official Maven image with Temurin JDK 21 (not the Alpine variant,
# which installs a different JDK version via apk).
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy POM first and resolve deps (layer-cached until pom.xml changes)
COPY pom.xml .
RUN mvn -B dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn -B package -DskipTests

# ─── Runtime Stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# Install curl for health check, adb for remote desktop feature
RUN apt-get update -q && apt-get install -y --no-install-recommends curl android-tools-adb \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user with home dir (needed for ADB keys)
RUN groupadd -r msgagent && useradd -r -g msgagent -m msgagent

WORKDIR /app

# Copy fat JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Health check uses the actuator endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD curl -sf http://localhost:9090/actuator/health || exit 1

USER msgagent
EXPOSE 9090 2775

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
