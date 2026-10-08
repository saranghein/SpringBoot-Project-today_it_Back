# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY apps/api/build.gradle ./apps/api/build.gradle
COPY apps/data-worker/build.gradle ./apps/data-worker/build.gradle
COPY domains/recommendation/build.gradle ./domains/recommendation/build.gradle
COPY integrations/external-data/build.gradle ./integrations/external-data/build.gradle

RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew :apps:data-worker:dependencies --no-daemon

COPY apps ./apps
COPY domains ./domains
COPY integrations ./integrations

RUN ./gradlew :apps:data-worker:bootJar --no-daemon \
    && cp apps/data-worker/build/libs/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S todayit \
    && adduser -S -G todayit -h /app todayit

WORKDIR /app

COPY --from=builder --chown=todayit:todayit /workspace/app.jar ./app.jar

USER todayit

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

