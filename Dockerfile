# syntax=docker/dockerfile:1
# Application image (architecture.md ADR-07, §10.1): multi-stage build on Eclipse Temurin with
# the Tesseract native packages that Tess4J needs. Built by the CI `container` job as
# invoice-intel:ci and deployed unchanged in Phase 2 (deployment.md).

# --- Build: compile and package the Spring Boot jar, then split it into layers ----------
FROM eclipse-temurin:25-jdk-resolute AS build
WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts gradle.lockfile settings-gradle.lockfile ./
COPY gradle/ gradle/
COPY buildSrc/ buildSrc/
COPY common/ common/
COPY storage/ storage/
COPY ingest/ ingest/
COPY text/ text/
COPY pii/ pii/
COPY extract/ extract/
COPY validate/ validate/
COPY reconcile/ reconcile/
COPY pipeline/ pipeline/
COPY eval/ eval/
COPY app/ app/

# Tests and static analysis run in their own CI jobs; this stage only packages.
RUN ./gradlew --no-daemon :app:bootJar \
    && java -Djarmode=tools -jar app/build/libs/app.jar extract --layers --launcher --destination extracted

# --- Runtime: JRE + Tesseract, non-root ------------------------------------------------
FROM eclipse-temurin:25-jre-resolute

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr=5.5.0-1build1 \
        tesseract-ocr-eng=1:4.1.0-2build1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home app

WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# Numeric so the runtime can check it is non-root without resolving names (hadolint DL3066).
USER 10001:10001
EXPOSE 8080

# Memory budget for the 2 GB container (§10.1, NFR-10); override via JAVA_TOOL_OPTIONS.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
