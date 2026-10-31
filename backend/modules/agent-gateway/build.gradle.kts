// Module: agent-gateway (read-only tool endpoints for agents, proposal intake, untrusted-text extractor, kill switch)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    testImplementation(project(":platform:testing"))
}
