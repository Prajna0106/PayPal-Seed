// Module: agent-gateway (read-only tool endpoints for agents, proposal intake, untrusted-text extractor, kill switch)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    // Read tools are served from these modules' public APIs; proposals go to governance.
    implementation(project(":modules:savings"))
    implementation(project(":modules:lending"))
    implementation(project(":modules:hardship"))
    implementation(project(":modules:underwriting"))
    implementation(project(":modules:governance"))
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":platform:testing"))
}

// Agent evals (04 §14): the injection corpus and the narrator guardrail datasets under agents/.
// They also run as part of `test`; this task runs just the evals: ./gradlew :modules:agent-gateway:eval
tasks.register<Test>("eval") {
    description = "Runs the agent eval suites (injection corpus, narrator guardrails)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("eval")
    }
}
