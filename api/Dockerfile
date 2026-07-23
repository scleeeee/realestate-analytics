FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY api/build.gradle.kts api/build.gradle.kts
COPY ingest/build.gradle.kts ingest/build.gradle.kts
COPY api/src api/src

RUN ./gradlew :api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/api/build/libs/api-0.1.0.jar app.jar
USER spring:spring
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O - http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
