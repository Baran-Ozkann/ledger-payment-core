# The quick start asks for Docker and nothing else, so the jar is built here rather than on the
# host. Two stages: a JDK to build with, a JRE to run on, and none of Maven in the final image.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# The wrapper and the POM alone first. Resolving Spring Boot's dependency tree is the slow half of
# this build and it only has to happen again when the POM changes, not when a line of Java does.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Tests need Testcontainers, which needs a Docker daemon this build does not have. They run in CI
# and in `./mvnw verify`; skipping them here does not skip them anywhere they were being run.
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Nothing here needs root, and the ledger is the one process in this stack that takes requests.
RUN addgroup -S ledger && adduser -S -G ledger ledger
COPY --from=build --chown=ledger:ledger /build/target/ledger-*.jar app.jar
USER ledger

# 8080 is the API, 8081 the management connector Prometheus scrapes.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
