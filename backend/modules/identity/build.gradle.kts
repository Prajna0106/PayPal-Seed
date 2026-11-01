// Module: identity (members keyed by PayPal payer id, staff, consent ledger, language, wallet preference)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    testImplementation(project(":platform:testing"))
}
