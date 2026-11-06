# Multi-stage Docker build for PayPal Seed Spring Boot Modular Monolith (Java 21)
# Uses Debian-based Temurin (glibc) and non-root user so embedded PostgreSQL (jOOQ codegen) runs cleanly
FROM eclipse-temurin:21-jdk AS builder

# Create non-root build user (PostgreSQL initdb refuses to run as root)
RUN groupadd -r gradle && useradd -r -g gradle -m -d /home/gradle gradle

WORKDIR /app
RUN chown -R gradle:gradle /app

# Copy gradle wrapper & build logic
COPY --chown=gradle:gradle gradlew gradlew.bat ./
COPY --chown=gradle:gradle gradle/ ./gradle/
COPY --chown=gradle:gradle build-logic/ ./build-logic/
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts ./

RUN chmod +x ./gradlew

# Copy all source trees
COPY --chown=gradle:gradle app/ ./app/
COPY --chown=gradle:gradle architecture-tests/ ./architecture-tests/
COPY --chown=gradle:gradle engines/ ./engines/
COPY --chown=gradle:gradle modules/ ./modules/
COPY --chown=gradle:gradle platform/ ./platform/
COPY --chown=gradle:gradle tools/ ./tools/

# Switch to non-root user for Gradle and EmbeddedPostgres initdb
USER gradle

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
