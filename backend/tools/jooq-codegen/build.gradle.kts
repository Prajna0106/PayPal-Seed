// Build tool: generates the jOOQ classes of one module schema from that module's Flyway migrations.
plugins {
    `java-library`
}

dependencies {
    implementation("org.jooq:jooq-codegen")
    implementation("org.jooq:jooq-meta")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("io.zonky.test:embedded-postgres:2.0.7")
    // Same server major as production and the integration tests (Render Postgres 16).
    implementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
    runtimeOnly("org.slf4j:slf4j-nop")
}
