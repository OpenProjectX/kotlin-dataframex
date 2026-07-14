# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=ghcr.io/openprojectx/dockerhub/library/maven:3.9.16-eclipse-temurin-17

# Publish this checkout to Maven Local first. This makes the dependency bundle
# self-contained even when the requested DataFrameX version is not on Maven Central yet.
FROM ${MAVEN_IMAGE} AS local-publisher

ARG PROJECT_VERSION=0.1.0-SNAPSHOT
ENV CI=true
WORKDIR /workspace

COPY . .

RUN ./gradlew --no-daemon --no-configuration-cache \
        -Pversion="${PROJECT_VERSION}" \
        :core:publishToMavenLocal \
        :example:publishToMavenLocal \
        -x test \
    && test -f "/root/.m2/repository/org/openprojectx/kotlin/dataframex/core/${PROJECT_VERSION}/core-${PROJECT_VERSION}.jar" \
    && test -f "/root/.m2/repository/org/openprojectx/kotlin/dataframex/example/${PROJECT_VERSION}/example-${PROJECT_VERSION}.jar"

# Maven resolves the POM after the local publications have been copied in. The
# resulting repository contains local artifacts plus all example runtime dependencies.
FROM ${MAVEN_IMAGE} AS dependency-resolver

ARG PROJECT_VERSION=0.1.0-SNAPSHOT
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY --from=local-publisher /root/.m2/repository /root/.m2/repository

RUN mvn --batch-mode --no-transfer-progress \
        -Ddataframex.version="${PROJECT_VERSION}" \
        org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline \
    && mvn --batch-mode --no-transfer-progress \
        -Ddataframex.version="${PROJECT_VERSION}" \
        org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory=/workspace/dependencies \
    && test -f "/workspace/dependencies/core-${PROJECT_VERSION}.jar" \
    && test -f "/workspace/dependencies/example-${PROJECT_VERSION}.jar" \
    && test -f "/workspace/dependencies/dataframe-1.0.0-Beta5.jar" \
    && test -f "/workspace/dependencies/kandy-lets-plot-0.8.4.jar" \
    && test -f "/workspace/dependencies/kotlin-gradle-plugin-2.3.20.jar" \
    && test -f "/workspace/dependencies/gradle-kotlin-dsl-plugins-6.5.7.jar" \
    && test -f "/root/.m2/repository/org/gradle/kotlin/kotlin-dsl/org.gradle.kotlin.kotlin-dsl.gradle.plugin/6.5.7/org.gradle.kotlin.kotlin-dsl.gradle.plugin-6.5.7.pom"

# This is a data image. Create a stopped container and use `docker cp` to extract
# either the canonical Maven repository or the convenient flat runtime jar directory.
FROM ghcr.io/openprojectx/dockerhub/library/alpine:3.23

ARG PROJECT_VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.title="Kotlin DataFrameX dependency cache" \
      org.opencontainers.image.description="Maven repository and runtime jars for Kotlin DataFrameX examples" \
      org.opencontainers.image.source="https://github.com/OpenProjectX/kotlin-dataframex" \
      org.opencontainers.image.version="${PROJECT_VERSION}"

COPY --from=dependency-resolver /root/.m2/repository /m2/repository
COPY --from=dependency-resolver /workspace/dependencies /dependencies

CMD ["sh", "-c", "echo 'Copy /m2/repository or /dependencies from this image; see README.md.'"]
