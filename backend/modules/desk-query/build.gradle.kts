// Module: desk-query (read models for the desk (portfolio, queues, AG Grid server-side rows))
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
