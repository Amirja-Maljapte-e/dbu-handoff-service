# Handoff Service — ElevenLabs to Genesys Cloud CX human handoff
#
# Multi-stage build. The first stage compiles; the runtime image carries only a
# JRE and the application, so nothing from the build toolchain ships.

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies are resolved before the source is copied, so a code change does
# not invalidate the cached dependency layer.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# Tests run in the pipeline, not here. The image build should be deterministic
# and should not depend on a database being reachable.
RUN mvn -B clean package -DskipTests

# The jar is unpacked into Spring Boot layers so that dependencies, which rarely
# change, land in a different image layer from application classes, which change
# on every commit.
RUN java -Djarmode=layertools -jar target/handoff-service-*.jar extract --destination extracted

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# The service holds long-lived outbound WebSockets and accepts inbound webhooks.
# It never needs to run as root.
RUN groupadd --system --gid 1001 handoff \
 && useradd --system --uid 1001 --gid handoff --no-create-home handoff

WORKDIR /app

COPY --from=build --chown=handoff:handoff /build/extracted/dependencies/ ./
COPY --from=build --chown=handoff:handoff /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=handoff:handoff /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=handoff:handoff /build/extracted/application/ ./

USER handoff

EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container limit rather
# than the host's memory. Without it the heap is sized for the whole machine and
# the container is killed under load.
#
# ExitOnOutOfMemoryError is deliberate: an instance that has run out of memory
# cannot service its sockets, and a restart lets reconciliation reassign them.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. That matters
# more here than usual: graceful shutdown releases socket ownership so another
# instance can take over without waiting for the ownership key to expire.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

# Liveness only — readiness depends on PostgreSQL and is checked by the
# orchestrator, not by Docker.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health/liveness || exit 1
