// Module: lending (F2, F4: loans, schedules, repayments, allocation, aging, close of business)
plugins {
    `java-library`
    id("jooq-module")
}
dependencies {
    implementation(project(":engines:schedule"))
    implementation(project(":modules:ledger"))
    implementation(project(":modules:payments"))
}
