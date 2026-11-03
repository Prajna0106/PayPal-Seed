// Module: identity (members keyed by PayPal payer id, staff, consent ledger, language, wallet preference)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation("org.springframework.security:spring-security-crypto")
    implementation(libs.bouncycastle.prov)
    testImplementation(project(":platform:testing"))
}
