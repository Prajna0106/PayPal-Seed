// Module: engagement (reminders, SMS via Zapier, quiet hours, opt-out)
plugins {
    `java-library`
    id("jooq-module")
}
dependencies {
    implementation(project(":modules:lending"))
    implementation(project(":modules:payments"))
    implementation(project(":modules:hardship"))
}
