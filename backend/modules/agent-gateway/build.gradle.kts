// Module: agent-gateway (read-only tool endpoints for agents, proposal intake, untrusted-text extractor, kill switch)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    // Read tools are served from these modules' public APIs; proposals go to governance.
    implementation(project(":modules:lending"))
    implementation(project(":modules:hardship"))
    implementation(project(":modules:underwriting"))
    implementation(project(":modules:governance"))
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":platform:testing"))
}
