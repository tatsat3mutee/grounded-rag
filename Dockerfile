# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Extracted (not nested) jars: the AOT cache can only hold classes loaded from plain jar files.
RUN ./mvnw -B -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/grounded-rag-*.jar extract --layers --destination /layers

FROM eclipse-temurin:25-jre
# The JRE image ships no HTTP client; the health check needs one.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app
WORKDIR /app
# Layered: dependencies change rarely, so code-only changes rebuild in seconds.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
RUN mv grounded-rag-*.jar app.jar
COPY eval eval

# JDK 25 runtime choices (measured in docs/PERFORMANCE.md):
# - Compact object headers (JEP 519): 8-byte headers, about 7% less live heap here.
# - AOT cache (JEP 483/514/515): classes loaded, linked and profiled ahead of time; startup 4.2 s to 1.9 s.
#   The cache is only valid for the same flags, so the training run uses exactly JAVA_OPTS;
#   a mismatch is a silent warning ("Unable to use AOT cache"), not an error.
ENV JAVA_OPTS="-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75"
RUN java $JAVA_OPTS -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar \
 && chown -R app /app

USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s CMD curl -fsS localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:AOTCache=app.aot -jar app.jar \"$@\"", "--"]
