rootProject.name = "java-to-kotlin"

include("orchestrator")
include("j2k-plugin")

dependencyResolutionManagement {
    // `PREFER_SETTINGS` would block project-level `repositories { }` blocks.
    // The IntelliJ Platform Gradle plugin needs to declare its own repos
    // (intellijPlatform { defaultRepositories() }) so we leave the default
    // (`PREFER_PROJECT`) in place.
    repositories {
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
