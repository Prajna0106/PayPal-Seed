// Module: credit-record (F6: record projection, levels, signed share links, verification API)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":platform:security"))
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":platform:testing"))
}
