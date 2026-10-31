// Platform: testing (embedded Postgres, fake business clock)
plugins {
    `java-library`
}
dependencies {
    api(project(":platform:kernel"))
    api("io.zonky.test:embedded-postgres:2.0.7")
    // Pin the bundled server to the production major version (Render Postgres 16).
    implementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
}
