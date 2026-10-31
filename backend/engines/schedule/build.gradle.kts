// Pure engine: schedule (generate, allocate, age, reschedule) - pure Java, no Spring, no IO
plugins {
    `java-library`
}
dependencies {
    implementation(project(":platform:kernel"))
    testImplementation("net.jqwik:jqwik:1.8.5")
}
