// Module: payments (PayPal: executor (only write client), webhook ingress, normaliser, mandate reconciler)
plugins {
    `java-library`
    id("jooq-module")
}
dependencies {
    implementation(project(":engines:schedule"))
}
