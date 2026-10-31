// Platform: security (actor resolution, roles, internal service token, agent read-only scopes)
plugins {
    `java-library`
}
dependencies {
    api(project(":platform:kernel"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
