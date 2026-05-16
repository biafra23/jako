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

// The orchestrator drives this plugin via the `runIde` task that the
// IntelliJ Platform Gradle plugin auto-registers — see
// scripts/run-j2k-headless.sh. `runIde` builds the sandbox + launches a
// headless IntelliJ with this plugin loaded; our `jakoConvert`
// ApplicationStarter then runs and exits.
//
// `buildPlugin` produces a redistributable .zip for users who want to
// drop the plugin into a separately-managed IntelliJ instance (and is
// what CI uses to verify the plugin packages cleanly).

// `idea.trust.all.projects=true` skips the "Trust this project?" prompt
// that otherwise blocks `openOrImport` headlessly. We're never opening
// untrusted code via this driver — the orchestrator only points it at
// the user's own target project — so suppressing the prompt is the
// right call.
tasks.named<JavaExec>("runIde") {
    systemProperty("idea.trust.all.projects", "true")
}
