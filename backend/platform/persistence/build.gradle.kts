// Platform: persistence (per-module Flyway, business-date clock, jOOQ configuration)
plugins {
    `java-library`
}
dependencies {
    api(project(":platform:kernel"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-jooq")
    // jOOQ's Settings class carries JAXB annotations; javac warns (and -Werror fails) without them on the classpath.
    compileOnly("jakarta.xml.bind:jakarta.xml.bind-api")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}
