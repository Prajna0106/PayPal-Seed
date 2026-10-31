// Pure Java: no Spring and no IO. ModulithArchitectureTest enforces this.
plugins {
    `java-library`
}
dependencies {
    api(project(":platform:kernel"))
    testImplementation("net.jqwik:jqwik:1.8.5")
}
