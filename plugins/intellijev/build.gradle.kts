import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "dev.intellijev"
version = "0.1.0"

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
// Its tests read JSON fixtures from the classpath (JevClientTest, the sketcher parity tests).
sourceSets.named("test") { resources.srcDir("../context-packer/src/test/resources") }

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }
}

// Keys live in the repo-root .env (gitignored). Hand them to the sandbox IDE and to tests.
val dotEnv: Map<String, String> = rootDir.resolve("../../.env").takeIf { it.isFile }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }

tasks.test {
    environment(dotEnv)
}

// Licence-free sandbox with the MCP server on and no first-run dialogs.
// OPEN_PROJECT=/path/to/project ./gradlew runIde
tasks.named<RunIdeTask>("runIde") {
    environment(dotEnv)
    // Rank the candidate set the evaluation measured: Kotlin files only.
    environment("CONTEXT_PACKER_EXTENSIONS", System.getenv("CONTEXT_PACKER_EXTENSIONS") ?: "kt")
    maxHeapSize = "2g"
    jvmArgs(
        "-Didea.trust.all.projects=true",
        "-Djb.consents.confirmation.enabled=false",
        "-Djb.privacy.policy.text=<!--999.999-->",
        "-Dide.show.tips.on.startup.default.value=false",
        "-Dide.newUsersOnboarding=false",
    )
    System.getenv("OPEN_PROJECT")?.let { args(it) }
    val options = sandboxConfigDirectory.dir("options")
    doFirst {
        options.get().asFile.apply { mkdirs() }.resolve("mcpServer.xml").writeText(
            """<application><component name="McpServerSettings">""" +
                """<option name="enableMcpServer" value="true"/></component></application>""",
        )
    }
}
