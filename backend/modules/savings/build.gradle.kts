// Module: savings (F1: goals, deposits, withdrawals, top-ups, pause, streaks)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":modules:ledger"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
