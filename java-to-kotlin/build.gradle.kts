plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    // Headless J2K plugin (see :j2k-plugin). Pinned to v2 of the IntelliJ
    // Platform Gradle plugin — v1 (`org.jetbrains.intellij`) is deprecated.
    id("org.jetbrains.intellij.platform") version "2.2.1" apply false
}
