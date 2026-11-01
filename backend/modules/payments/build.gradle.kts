// Module: payments (PayPal: executor (only write client), webhook ingress, normaliser, mandate reconciler)
plugins {
    `java-library`
    id("jooq-module")
}
dependencies {
    implementation(project(":engines:schedule"))
    implementation(project(":platform:messaging"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
