# syntax=docker/dockerfile:1

# --- Build stage ---
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# 의존성 매니페스트 먼저 복사해 캐시 레이어 확보
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 후 실행 가능한 jar 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# 비루트 사용자로 실행
RUN adduser --disabled-password --gecos "" app
USER app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
