import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "intellijev"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Same IntelliJ Platform Gradle Plugin as the context engine's standalone build: 2.3.0 predates
// IntelliJ 2025.2 and cannot run its headless IDE for buildSearchableOptions or runIde.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
