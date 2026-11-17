# syntax=docker/dockerfile:1
FROM eclipse-temurin:24-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests && java -Djarmode=tools -jar target/grounded-rag-*.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:24-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
# Layered jar: dependencies change rarely, so code-only changes rebuild in seconds.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
COPY eval eval
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s CMD wget -qO- localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
