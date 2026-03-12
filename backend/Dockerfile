# ---- Build Stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn -B -q package -DskipTests

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="messaging-agent"
WORKDIR /app

RUN addgroup -S msgagent && adduser -S msgagent -G msgagent
USER msgagent

COPY --from=builder /app/target/messaging-agent-backend-*.jar app.jar

EXPOSE 8080 2775

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
