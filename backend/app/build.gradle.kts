plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:security"))
    implementation(project(":platform:observability"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
