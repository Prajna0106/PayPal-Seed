plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":app"))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-core")
}
