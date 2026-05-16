plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(17)
}

// Pin to a recent IntelliJ Community release that ships with the Kotlin
// plugin. The J2K converter lives in the bundled Kotlin plugin, so we
// need to depend on it (not just the platform itself).
//
// Note: the IntelliJ Platform plugin needs its own repositories declared
// in addition to mavenCentral (settings.gradle.kts has `PREFER_PROJECT`
// which keeps this from being blocked).
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.1.1")
        bundledPlugins(
            // The J2K converter ships as part of the bundled Kotlin plugin.
            "org.jetbrains.kotlin",
            // We need the Java plugin to parse / analyze .java sources.
            "com.intellij.java",
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "jako-j2k"
        // Required `<id>` in plugin.xml — must match.
        id = "com.biafra23.jako.j2k"
        version = "0.1.0"
        ideaVersion {
            // Loose bounds so we don't break on minor IDE bumps.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

// The plugin only ever runs headlessly via `idea.sh ourCommand <args>`;
// we don't need or want a `runIde` task for it. The `buildPlugin` task
// produces the .zip that the orchestrator points `idea.sh` at via
// `--plugin path/to/jako-j2k.zip`.
