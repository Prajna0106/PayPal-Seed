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
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
