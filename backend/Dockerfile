# 설계서 §21.1: non-root user, layered jar, health check.
# alpine 변형은 arm64(Apple Silicon, Graviton) 이미지가 없어 멀티 아키텍처 기본 이미지를 쓴다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
# 기반 이미지 이후에 나온 OS 보안 패치(예: openssl)를 빌드 때마다 반영한다.
RUN apt-get update && apt-get -y upgrade && rm -rf /var/lib/apt/lists/* \
 && groupadd --system devgraph && useradd --system --gid devgraph --home-dir /app --shell /usr/sbin/nologin devgraph \
 && mkdir -p /var/devgraph/export && chown devgraph:devgraph /var/devgraph/export
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER devgraph
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s CMD curl -fs http://localhost:${MANAGEMENT_PORT:-8080}/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
