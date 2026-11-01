// Module: desk-query (read models for the desk (portfolio, queues, AG Grid server-side rows))
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":engines:schedule"))
    // Only the api.events packages of these modules are consumed; nobody depends on desk-query.
    implementation(project(":modules:lending"))
    implementation(project(":modules:savings"))
    implementation(project(":modules:hardship"))
    implementation(project(":modules:underwriting"))
    implementation(project(":modules:governance"))
    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
