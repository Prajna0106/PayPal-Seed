plugins {
    base
}

allprojects {
    group = "com.paypalseed"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
        "implementation"(platform("org.springframework.modulith:spring-modulith-bom:1.2.4"))
        "testImplementation"(platform("org.springframework.modulith:spring-modulith-bom:1.2.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.release.set(21)
        options.encoding = "UTF-8"
        // Spring binds @PathVariable/@RequestParam by parameter name.
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-this-escape,-classfile", "-Werror"))
    }

    tasks.withType<Test> {
        // Scaffolded modules (rules, assessment, ...) still carry placeholder test classes with no tests.
        failOnNoDiscoveredTests = false
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik")
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Bounded-context modules: Spring application services over JDBC, publishing Modulith events.
configure(subprojects.filter { it.path.startsWith(":modules:") }) {
    dependencies {
        "implementation"(project(":platform:kernel"))
        "implementation"(project(":platform:persistence"))
        "implementation"(project(":platform:security"))
        "implementation"("org.springframework.boot:spring-boot-starter-jdbc")
        "implementation"("org.springframework.boot:spring-boot-starter-web")
        "implementation"("org.springframework.boot:spring-boot-starter-validation")
        "implementation"("org.springframework.modulith:spring-modulith-events-api")
        "implementation"("org.springframework.modulith:spring-modulith-api")
        "testImplementation"(project(":platform:testing"))
    }
}
