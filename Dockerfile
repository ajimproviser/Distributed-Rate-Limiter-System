# syntax=docker/dockerfile:1

# ---- Build stage ---------------------------------------------------------
# Dependencies resolve in their own layer so source-only edits skip the
# multi-minute re-download on every rebuild.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package


# ---- Runtime stage ------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Unprivileged user: nothing in this service needs root at runtime.
RUN addgroup -S -g 1001 app && adduser -S -u 1001 -G app app

COPY --from=build --chown=app:app /build/target/distributed-rate-limiter.jar app.jar

USER app
EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container limit rather
# than the host's total memory, which is what makes memory limits meaningful.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q --spider http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
