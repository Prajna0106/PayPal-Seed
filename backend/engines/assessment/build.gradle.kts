// Pure engine: assessment (fair-check numbers: income avg, lowest month, CV, saving consistency) - pure Java, no Spring, no IO
plugins {
    `java-library`
}
dependencies {
    implementation(project(":platform:kernel"))
    testImplementation("net.jqwik:jqwik:1.8.5")
}
