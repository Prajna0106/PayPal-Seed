// Module: lending (F2, F4: loans, schedules, repayments, allocation, aging, close of business)
plugins {
    `java-library`
    id("jooq-module")
}
dependencies {
    implementation(project(":engines:schedule"))
    implementation(project(":engines:rules"))
    implementation(project(":modules:ledger"))
    implementation(project(":modules:payments"))
    implementation(project(":modules:savings"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
