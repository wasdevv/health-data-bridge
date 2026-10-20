# One build, two images: --target bridge (FHIR + worker) and --target erp-mock.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY bridge-core/pom.xml bridge-core/
COPY erp-mock/pom.xml erp-mock/
COPY bridge-test-support/pom.xml bridge-test-support/
COPY bridge-fhir/pom.xml bridge-fhir/
COPY bridge-sync/pom.xml bridge-sync/
COPY bridge-app/pom.xml bridge-app/
RUN mvn -B -q dependency:go-offline -DexcludeGroupIds=dev.wasdevv.hdb || true
COPY . .
RUN mvn -B -q package -DskipTests -Djacoco.skip=true

FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
USER app

FROM runtime AS erp-mock
COPY --from=build /src/erp-mock/target/erp-mock-0.1.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8090
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
    CMD curl -fsS http://localhost:8090/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

FROM runtime AS bridge
COPY --from=build /src/bridge-app/target/bridge-app-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=24 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
