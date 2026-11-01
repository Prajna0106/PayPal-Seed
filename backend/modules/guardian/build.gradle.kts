// Module: guardian (F5: the Guardian agent; reads only through agent-gateway tools and files proposals there)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:agent-gateway"))
    implementation(project(":modules:hardship"))
}
