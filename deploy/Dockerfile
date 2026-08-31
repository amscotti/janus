# syntax=docker/dockerfile:1
# =============================================================================
# Janus — multi-stage native-image container build.
#
# Builder : GraalVM CE 25 pinned to the mise.toml toolchain version
# (graalvm-community-25.0.2 — keep in sync with mise.toml and
# gradle.properties). Ships native-image for linux/amd64 and
# linux/arm64; the Gradle wrapper (9.6+) runs the build and
# org.gradle.java.installations.fromEnv=JAVA_HOME resolves the
# toolchain from the builder's JDK (gradle.properties).
# Runtime : gcr.io/distroless/base:nonroot — glibc + ca-certificates (OS-level
# CA trust for upstream HTTPS, verified by the docker TLS
# probe) + non-root user 65532. NO curl/wget: the HEALTHCHECK uses a
# static busybox `wget` copied from a tools stage.
# Digest-pinned: the floating `nonroot` tag is pinned to its
# multi-arch index digest so a future distroless rebuild cannot
# silently change the CA bundle/glibc the TLS story depends on.
#
# ubi9-micro alternative (documented, NOT shipped): `registry.access.redhat.com/
# ubi9/ubi-micro:latest` — needs `curl` or `wget` + `libgcc` added explicitly
# (micro images are tar-only; use ubi9/ubi-minimal if you want a package
# manager). distroless base is preferred: smaller, non-root by default, ships
# CA certs.
#
# Config contract: the image's mainClass is JanusApplication (lesson — the
# CLI's `--config` sets micronaut.config.files BEFORE boot, so the image boots
# with the MICRONAUT_CONFIG_FILES env var instead). Mount a TOML config at
# /etc/janus/config.toml and set MICRONAUT_CONFIG_FILES=/etc/janus/config.toml.
# application.toml (classpath) provides defaults; the mounted file's [janus]
# section carries model-list/router/keys/store. Secrets stay out of the file:
# api-key-env names (DEEPSEEK_API_KEY, ...), master-key-env (JANUS_MASTER_KEY)
# and jdbc-url-env (JANUS_DB_URL) — the env-var-name pattern. No [janus.store]
# section => in-memory, zero-dependency; adding it with type = "postgres" switches the
# node to the shared-DB shape (fail-fast: an unreachable DB refuses boot).
#
# Memory math (measured): the binary embeds -O2 + -R:MaxHeapSize=256m;
# measured post-warmup RSS ~75-86 MiB (memory shape 75 MiB max / postgres shape
# higher with the JDBC pool). Container memory limits: ≥512Mi comfortable,
# 1Gi headroom. The builder needs a few GiB for native-image compilation —
# override NATIVE_IMAGE_MEM (GB) if your host/CI is constrained; it caps both
# the Gradle build JVM and the forked native-image compile JVM (-J-Xmx).
#
# Arch note: this host is Apple Silicon => local builds produce linux/arm64;
# amd64 images come from the CI workflow (.github/workflows/docker.yml, x64
# runners). Record the arch with every measured number (methodology).
# =============================================================================

# ------------------------------------------------------------------ builder
FROM ghcr.io/graalvm/graalvm-community:25.0.2-ol9 AS builder

ARG NATIVE_IMAGE_MEM=6

# C26 (distribution integrity): GRADLE_DIST_SHA256 is Gradle's official
# gradle-<v>-bin.zip.sha256, passed bygithub/workflows/docker.yml (fetched
# via scripts/pin-gradle-digest.sh). When set it pins distributionSha256Sum
# before the first ./gradlew run below, so a tampered/corrupted distribution
# download fails this build. Empty (plain local builds) leaves the wrapper
# exactly as before — run scripts/pin-gradle-digest.sh locally to pin too.
ARG GRADLE_DIST_SHA256=""

# native-image is bundled in the CE image (verify; fallback: gu install
# native-image). Non-root build user per GraalVM image docs (uid 1000).
RUN useradd -m -u 1000 builder && mkdir -p /home/builder/.gradle && chown -R builder /home/builder
USER builder
WORKDIR /src

# Copy the repo (respecting .dockerignore). Wrapper first for layer caching is
# moot here (BuildKit cache mounts below carry the Gradle caches across builds).
COPY --chown=builder . .

# BuildKit cache: ~/.gradle (wrapper dist + dependency/toolchain caches) keeps
# rebuilds fast. The module build dirs are deliberately NOT cache-mounted —
# BuildKit cache mounts are discarded when the RUN finishes, and the native
# binary must survive into the image layer (the runtime COPY reads it from the
# builder's real filesystem). Native-image compilation needs several GiB of RAM
# — the documented knob is the NATIVE_IMAGE_MEM build-arg (default 6 GB). The
# cache mount is created root-owned unless uid/gid is pinned — the builder
# user (1000) writes it, so it carries uid=1000,gid=1000.
# NATIVE_IMAGE_MEM caps BOTH JVMs the build spawns: the Gradle daemon/build
# JVM (-Dorg.gradle.jvmargs, left) AND the forked native-image compile JVM
# (-PnativeImageMem => -J-Xmx, right — the plugin execs native-image as a
# separate process whose heap the Gradle flag cannot reach; without it a
# constrained builder still OOMs in native compilation while Gradle looks fine).
RUN --mount=type=cache,id=gradle-cache,target=/home/builder/.gradle,uid=1000,gid=1000 \
    if [ -n "${GRADLE_DIST_SHA256}" ]; then \
      printf '\ndistributionSha256Sum=%s\n' "${GRADLE_DIST_SHA256}" >> gradle/wrapper/gradle-wrapper.properties; \
    fi && \
    JAVA_HOME="${JAVA_HOME:-/usr/lib/graalvm}" \
    ./gradlew :janus-gateway:nativeCompile --no-daemon \
      -Dorg.gradle.jvmargs="-Xmx${NATIVE_IMAGE_MEM}g -Dfile.encoding=UTF-8" \
      -PnativeImageMem="${NATIVE_IMAGE_MEM}"

# ------------------------------------------------------------------ tools
# Static busybox for the runtime HEALTHCHECK (distroless has no curl/wget).
# busybox:1.36 is a static musl build — its wget works on glibc distroless.
FROM busybox@sha256:73aaf090f3d85aa34ee199857f03fa3a95c8ede2ffd4cc2cdb5b94e566b11662 AS tools

# ------------------------------------------------------------------ runtime
FROM gcr.io/distroless/base@sha256:97b9d04bed1c754b756c3c4b6a04915c22fb0b5d96a59944eb3bf78c26e6e157 AS runtime

# The tuned native binary + busybox wget (HEALTHCHECK only).
COPY --from=builder /src/janus-gateway/build/native/nativeCompile/janus /usr/local/bin/janus
COPY --from=tools /bin/wget /usr/local/bin/wget

# Distroless nonroot runs as uid 65532; the config mount is read-only and the
# runtime writes nothing locally (HikariCP/native temp files land in a tmpfs
# /tmp mounted by compose/k8s — the manifests do that).
USER 65532:65532

EXPOSE 8080

# busybox wget returns 0 on HTTP 2xx/3xx only. Probe /health/readiness: the
# Micronaut management readiness endpoint is the compose-healthcheck contract
# (K8s probes use the same path — kubelet executes them, no binary needed).
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/usr/local/bin/wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/health/readiness"]

ENTRYPOINT ["/usr/local/bin/janus"]
