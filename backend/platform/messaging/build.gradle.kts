// Platform: messaging (outbox, inbox dedupe, event publication, retry + dead letter)
plugins {
    `java-library`
}

dependencies {
    implementation("org.springframework:spring-context")
    implementation("org.slf4j:slf4j-api")
}
