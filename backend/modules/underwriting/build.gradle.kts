// Module: underwriting (F3: consented data fetch, assessment records, card requests to agent)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    testImplementation(project(":platform:testing"))
}
