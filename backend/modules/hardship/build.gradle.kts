// Module: hardship (F5: signals, cases, options offered/chosen, apply via schedule engine)
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    // The facade exposes the engine's grounding types, so consumers see them too.
    api(project(":engines:rules"))
    implementation(project(":platform:messaging"))
    implementation(project(":modules:lending"))
    implementation(project(":modules:governance"))
    implementation(project(":modules:underwriting"))
    testImplementation(project(":platform:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
