FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd -r msgagent && useradd -r -g msgagent -m msgagent
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
USER msgagent
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
