// Module: underwriting (F3: consented data fetch, assessment records, card requests to agent)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":engines:rules"))
    implementation(project(":engines:assessment"))
    implementation(project(":modules:savings"))
    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
