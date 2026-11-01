// Module: identity (member, staff, consent (grant/revoke), wallet preference, language)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":modules:payments"))
    testImplementation(project(":platform:testing"))
}
