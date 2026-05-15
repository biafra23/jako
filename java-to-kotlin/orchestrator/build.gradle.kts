plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Java AST extraction for phase 0 source-inventory + dep graph.
    implementation("com.github.javaparser:javaparser-core:3.26.2")

    // YAML config via kotlinx-serialization.
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    // JSON state + analysis artefacts.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("jako.MainKt")
    applicationName = "orchestrator"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
