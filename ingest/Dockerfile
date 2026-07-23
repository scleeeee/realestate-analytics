FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY api/build.gradle.kts api/build.gradle.kts
COPY ingest/build.gradle.kts ingest/build.gradle.kts
COPY ingest/src ingest/src

RUN ./gradlew :ingest:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/ingest/build/libs/ingest-0.1.0.jar app.jar
USER spring:spring
ENTRYPOINT ["java", "-jar", "app.jar"]
