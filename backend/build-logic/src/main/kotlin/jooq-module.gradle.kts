// Convention plugin: generates the jOOQ classes of the module's own schema.
//
// Each module generates only its own schema, into its own infrastructure.persistence.jooq package, so
// another module's tables are not even on its compile classpath. The task is cacheable: it reruns
// only when the module's migrations change.
plugins {
    `java-library`
}

val schema = project.name.replace("-", "")
val jooqPackage = "com.paypalseed.$schema.infrastructure.persistence.jooq"
val migrations = layout.projectDirectory.dir("src/main/resources/db/migration/${project.name}")
val generated = layout.buildDirectory.dir("generated-src/jooq/main")

val jooqGenerator by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
    jooqGenerator(project(":tools:jooq-codegen"))
}

val generateJooq = tasks.register<JavaExec>("generateJooq") {
    group = "build"
    description = "Generates jOOQ classes for the $schema schema from its Flyway migrations"
    classpath = jooqGenerator
    mainClass.set("com.paypalseed.tools.jooq.GenerateJooq")
    inputs.dir(migrations).withPropertyName("migrations").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("schema", schema)
    inputs.property("package", jooqPackage)
    outputs.dir(generated)
    outputs.cacheIf { true }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(schema, migrations.asFile.absolutePath, generated.get().asFile.absolutePath, jooqPackage)
    })
}

sourceSets.named("main") {
    java.srcDir(generateJooq)
}
