# Multi-stage Docker build for PayPal Seed Spring Boot Modular Monolith (Java 21)
# Uses Debian-based Temurin (glibc) so embedded PostgreSQL (jOOQ codegen) runs cleanly
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy gradle wrapper & configuration
COPY gradlew gradlew.bat ./
COPY gradle/ ./gradle/
COPY build-logic/ ./build-logic/
COPY settings.gradle.kts build.gradle.kts ./

RUN chmod +x ./gradlew

# Copy all source trees
COPY app/ ./app/
COPY architecture-tests/ ./architecture-tests/
COPY engines/ ./engines/
COPY modules/ ./modules/
COPY platform/ ./platform/
COPY tools/ ./tools/

# Build the Spring Boot executable jar (skipping unit/integration tests during image build)
RUN ./gradlew :app:bootJar -x test --no-daemon

# Production runtime container
FROM eclipse-temurin:21-jre AS runner

WORKDIR /app

# Run as non-root user
RUN groupadd -r seed && useradd -r -g seed seed

COPY --from=builder --chown=seed:seed /app/app/build/libs/*.jar /app/seed.jar

USER seed

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/seed.jar"]
