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
COPY perf/ perf/

# Tests and static analysis run in their own CI jobs; this stage only packages. The pinned NER
# model (ADR-15) is downloaded here with every file's SHA-256 checked and staged at
# pii/build/ner-model, so the runtime never downloads anything.
# The download writes 0600 temp files, so the model is made world-readable for the app user.
RUN ./gradlew --no-daemon :app:bootJar :pii:stageNerModel -PmodelCacheDir=/workspace/models \
    && chmod -R a+rX pii/build/ner-model \
    && java -Djarmode=tools -jar app/build/libs/app.jar extract --layers --launcher --destination extracted

# --- Runtime: JRE + Tesseract, non-root ------------------------------------------------
FROM eclipse-temurin:25-jre-resolute

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr=5.5.0-1build1 \
        tesseract-ocr-eng=1:4.1.0-2build1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --no-log-init --uid 10001 --gid app --create-home --home-dir /home/app app

WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
# NER layer of the PII stage (PII-2(b), ADR-15); start-up fails without it while NER is on.
COPY --from=build /workspace/pii/build/ner-model/ /app/models/ner/
ENV NER_MODEL_DIR=/app/models/ner

# Numeric so the runtime can check it is non-root without resolving names (hadolint DL3066).
USER 10001:10001
EXPOSE 8080

# Memory budget for the 2 GB container (§10.1, NFR-10); override via JAVA_TOOL_OPTIONS. DJL stays
# offline: its natives come from the jars and are extracted to the app user's home (~/.djl.ai),
# ONNX Runtime's to the temp dir.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Dai.djl.offline=true"
# Tess4J (JNA) and OpenCV load native libraries (ADR-01, architecture.md §6.2).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "org.springframework.boot.loader.launch.JarLauncher"]
