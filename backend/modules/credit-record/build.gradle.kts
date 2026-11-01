// Module: credit-record (F6: record projection, levels, signed share links, verification API)
// Depends on no other business module: facts reach it through its facade, from the app module's wiring (ADR 0014).
plugins {
    `java-library`
    id("jooq-module")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":engines:rules"))
    testImplementation(project(":platform:testing"))
    // jOOQ settings carry JAXB annotations; javac warns (and -Werror fails) without them when a test builds a Configuration.
    testCompileOnly("jakarta.xml.bind:jakarta.xml.bind-api")
}
