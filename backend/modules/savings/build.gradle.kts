// Module: savings (F1: goals, deposits, withdrawals, top-ups, pause, streaks)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    testImplementation(project(":platform:testing"))
}
