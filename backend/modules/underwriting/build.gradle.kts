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
    // Consent follows identity's ledger; the PayPal account reference comes from identity (ADR 0018).
    implementation(project(":modules:identity"))
    // Consented income is read through the payments facade, never from PayPal directly.
    implementation(project(":modules:payments"))
    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
