// Module: fraud (F10: signals (Elastic), flags, dispute evidence packs)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    testImplementation(project(":platform:testing"))
}
