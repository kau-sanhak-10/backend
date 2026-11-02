# 1단계: 실행용 jar 빌드 (JDK)
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# 빌드 설정만 먼저 복사해 의존성을 받아둠 → 소스만 바뀌면 이 단계는 캐시 재사용
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# 테스트는 CI에서 돌리므로 실행용 jar만 생성
COPY src src
RUN ./gradlew bootJar --no-daemon

# 2단계: 실행 (JRE)
FROM eclipse-temurin:21-jre
WORKDIR /app

# HEALTHCHECK용 curl
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# root가 아닌 전용 사용자로 실행
RUN groupadd --system --gid 10001 spring \
    && useradd --system --uid 10001 --gid spring --no-create-home spring

COPY --from=builder --chown=spring:spring /app/build/libs/*.jar app.jar

USER spring
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]