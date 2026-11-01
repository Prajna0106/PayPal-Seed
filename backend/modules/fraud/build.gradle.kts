// Module: fraud (F10: signals, flags, dispute evidence packs; Postgres only, no search engine)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:messaging"))
    implementation(project(":modules:governance"))
    implementation(project(":modules:lending"))
    implementation(project(":modules:payments"))
    implementation(project(":modules:savings"))
    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
