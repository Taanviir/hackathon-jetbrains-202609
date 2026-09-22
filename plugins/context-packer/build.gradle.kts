import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Bundled by the IDE. Compile against it, never ship a copy.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

// Keys live in the repo-root .env (gitignored). Hand them to the sandbox IDE and to tests.
val dotEnv: Map<String, String> = rootDir.resolve("../../.env").takeIf { it.isFile }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }

tasks.withType<RunIdeTask>().configureEach {
    environment(dotEnv)
    maxHeapSize = "2g"
}

tasks.test {
    environment(dotEnv)
    System.getenv("EVAL")?.let { environment("EVAL", it) }
}

// A licence-free sandbox for automated checks: Community edition, MCP server on, no first-run dialogs.
// OPEN_PROJECT=/path/to/project ./gradlew runIdeCommunity
intellijPlatformTesting {
    runIde {
        register("runIdeCommunity") {
            type = IntelliJPlatformType.IntellijIdeaCommunity
            version = "2025.2.6.2"
            task {
                environment(dotEnv)
                // The demo ranks the candidate set the eval measured: Kotlin files only.
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
        }
    }
}
