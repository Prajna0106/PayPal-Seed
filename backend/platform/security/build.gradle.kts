// Platform: security (actor resolution, roles, signed actor tokens, internal service token, agent read-only scopes)
plugins {
    `java-library`
}
dependencies {
    api(project(":platform:kernel"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.nimbus.jose.jwt)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// ./gradlew :platform:security:mintActorToken -Ptype=staff -Pid=<uuid> -Proles=OFFICER   (needs SEED_ACTOR_TOKEN_SECRETS)
tasks.register<JavaExec>("mintActorToken") {
    group = "help"
    description = "Prints a dev actor token signed with the first key of SEED_ACTOR_TOKEN_SECRETS"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.paypalseed.platform.security.ActorTokenTool")
    args(listOfNotNull(findProperty("type") as String?, findProperty("id") as String?, findProperty("roles") as String?))
}
