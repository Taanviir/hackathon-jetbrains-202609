import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "dev.intellijev"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    // Context ranking uses the IDE-bundled JSON runtime; do not package another copy.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    intellijPlatform {
        intellijIdeaCommunity("2025.2.6.2")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

// One IntelliJev distribution includes the existing, independently tested context engine.
// Keep its source in place so the evaluation tools and teammate work remain undisturbed.
kotlin.sourceSets.named("main") { kotlin.srcDir("../context-packer/src/main/kotlin") }
kotlin.sourceSets.named("test") { kotlin.srcDir("../context-packer/src/test/kotlin") }

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }
}

tasks {
    withType<RunIdeTask> {
        jvmArgs("-Xmx2g")
    }
}
