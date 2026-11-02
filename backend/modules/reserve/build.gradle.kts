// Module: reserve (F8: donations, coverage, cover requests)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:messaging"))
    implementation(project(":modules:ledger"))
    implementation(project(":modules:governance"))
    implementation(project(":modules:lending"))
    implementation(project(":modules:payments"))
    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
