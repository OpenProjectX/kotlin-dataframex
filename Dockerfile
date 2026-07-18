# syntax=docker/dockerfile:1.7

ARG DEPENDENCY_BUNDLE_IMAGE=ghcr.io/openprojectx/gradle-dependency-bundle:0.1.1
ARG BUILD_IMAGE=ghcr.io/openprojectx/dockerhub/library/maven:3.9.16-eclipse-temurin-17

# The plugin image is both the bootstrap repository and the independently runnable
# JFrog auditor. Copying its Maven repository avoids a plugin-portal bootstrap
# dependency inside this build.
FROM ${DEPENDENCY_BUNDLE_IMAGE} AS dependency-bundle-plugin

FROM ${BUILD_IMAGE} AS bundle-builder

ARG PROJECT_VERSION=0.1.0-SNAPSHOT
ARG USE_MIRRORS=true
ENV CI=true \
    USE_MIRRORS=${USE_MIRRORS}
WORKDIR /workspace

COPY --from=dependency-bundle-plugin /m2/repository /root/.m2/repository
COPY . .

RUN ./gradlew --no-daemon --no-configuration-cache \
        -Pversion="${PROJECT_VERSION}" \
        -PdependencyBundleOutput=/bundle \
        prepareDependencyBundle \
        -x test \
    && test -f "/bundle/m2/repository/org/openprojectx/kotlin/dataframex/core/${PROJECT_VERSION}/core-${PROJECT_VERSION}.jar" \
    && test -f "/bundle/m2/repository/org/openprojectx/kotlin/dataframex/example/${PROJECT_VERSION}/example-${PROJECT_VERSION}.jar" \
    && test -f "/bundle/m2/repository/org/jetbrains/kotlinx/dataframe/1.0.0-Beta5/dataframe-1.0.0-Beta5.module" \
    && test -s "/bundle/m2/repository/com/squareup/kotlinpoet/2.3.0/kotlinpoet-2.3.0.jar" \
    && test -s "/bundle/m2/repository/com/squareup/kotlinpoet/2.3.0/kotlinpoet-2.3.0-sources.jar" \
    && test -f "/bundle/m2/repository/org/gradle/kotlin/gradle-kotlin-dsl-plugins/6.6.4/gradle-kotlin-dsl-plugins-6.6.4.jar" \
    && test -f /bundle/dependency-graph.json \
    && test -f /bundle/dependency-graph.txt \
    && test -n "$(find /bundle/dependencies -name 'kandy-lets-plot-0.8.4.jar' -print -quit)" \
    && for variant in gradle80 gradle81 gradle82 gradle85 gradle86 gradle88 gradle811 gradle813; do \
         test -f "/bundle/m2/repository/org/jetbrains/kotlin/kotlin-gradle-plugin/2.3.21/kotlin-gradle-plugin-2.3.21-${variant}.jar"; \
         test -f "/bundle/m2/repository/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.3.21/kotlin-gradle-plugin-api-2.3.21-${variant}.jar"; \
       done

# Prove that an empty Gradle module cache can compile Kotlin DSL build logic and
# run the independent smoke build using only the generated Maven repository.
FROM bundle-builder AS offline-verifier

ARG PROJECT_VERSION=0.1.0-SNAPSHOT
ENV OFFLINE_M2_REPO=/bundle/m2/repository \
    DATAFRAMEX_VERSION=${PROJECT_VERSION}

RUN mkdir -p /tmp/offline-gradle \
    && cp -R /root/.gradle/wrapper /tmp/offline-gradle/wrapper \
    && GRADLE_USER_HOME=/tmp/offline-gradle ./gradlew \
         --no-daemon \
         --no-configuration-cache \
         --offline \
         -p docker/offline-smoke-test \
         clean build

FROM ghcr.io/openprojectx/dockerhub/library/alpine:3.23

ARG PROJECT_VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.title="Kotlin DataFrameX dependency bundle" \
      org.opencontainers.image.description="Portable Maven repository and dependency graph for Kotlin DataFrameX" \
      org.opencontainers.image.source="https://github.com/OpenProjectX/kotlin-dataframex" \
      org.opencontainers.image.version="${PROJECT_VERSION}"

COPY --from=offline-verifier /bundle/m2/repository /m2/repository
COPY --from=offline-verifier /bundle/dependencies /dependencies
COPY --from=offline-verifier /bundle/dependency-graph.json /dependency-bundle/dependency-graph.json
COPY --from=offline-verifier /bundle/dependency-graph.txt /dependency-bundle/dependency-graph.txt

CMD ["sh", "-c", "echo 'Copy /m2/repository or inspect /dependency-bundle; see docs/dependency-image.md.'"]
