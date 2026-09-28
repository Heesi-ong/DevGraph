# 설계서 §21.1: non-root user, layered jar, health check.
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S devgraph && adduser -S devgraph -G devgraph
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER devgraph
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
