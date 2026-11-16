# Build stage: Maven runs inside Docker, so nothing extra is needed on the host.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests need PostgreSQL, so they run separately with `make test`.
RUN mvn -q -B package -DskipTests

# Runtime stage: just a JRE and the app jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/shipmenttracker.jar app.jar
RUN useradd --system --uid 10001 app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
