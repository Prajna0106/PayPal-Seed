// Pure engine: rules (eligibility ladder, sizing, hardship options, approval tiers, fraud blocks) - pure Java, no Spring, no IO
plugins {
    `java-library`
}
dependencies {
    implementation(project(":platform:kernel"))
    testImplementation("net.jqwik:jqwik:1.8.5")
}
