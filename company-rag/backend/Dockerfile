FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
ARG OTEL_VERSION=2.22.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/opentelemetry-javaagent.jar /opt/otel.jar
RUN useradd -u 10001 -m app && mkdir -p /data/documents && chown -R app:app /data /opt/otel.jar
COPY --from=build /build/target/company-rag-0.1.0.jar /opt/app.jar
USER app
EXPOSE 8080 9091
ENTRYPOINT ["java","-javaagent:/opt/otel.jar","-jar","/opt/app.jar"]
