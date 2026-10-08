# syntax=docker/dockerfile:1.7

# 빌드 환경의 플랫폼을 사용해 API 실행 파일을 생성
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# 의존성 정의 파일을 먼저 복사해 소스 변경 시에도 Gradle 의존성 캐시를 재사용
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY apps/api/build.gradle ./apps/api/build.gradle
COPY apps/data-worker/build.gradle ./apps/data-worker/build.gradle
COPY domains/recommendation/build.gradle ./domains/recommendation/build.gradle
COPY integrations/external-data/build.gradle ./integrations/external-data/build.gradle

RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew :apps:api:dependencies --no-daemon

# 애플리케이션 소스를 복사한 뒤 API 모듈의 실행 가능한 JAR을 빌드
COPY apps ./apps
COPY domains ./domains
COPY integrations ./integrations

RUN ./gradlew :apps:api:bootJar --no-daemon \
    && cp apps/api/build/libs/*.jar /workspace/app.jar

# 실행 이미지에는 JRE와 빌드 결과물만 포함
FROM eclipse-temurin:21-jre-alpine AS runtime

# 컨테이너를 비루트 사용자로 실행
RUN addgroup -S todayit \
    && adduser -S -G todayit -h /app todayit

WORKDIR /app

COPY --from=builder --chown=todayit:todayit /workspace/app.jar ./app.jar

USER todayit

# API 서버가 사용하는 기본 HTTP 포트
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

