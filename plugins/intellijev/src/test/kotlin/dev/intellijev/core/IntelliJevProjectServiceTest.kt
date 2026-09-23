package dev.intellijev.core

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class IntelliJevProjectServiceTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = Path.of("src", "test").toAbsolutePath().toString()

    fun testEmptyContextDoesNotRequestAnExplanation() {
        val service = IntelliJevProjectService(project)

        assertEquals(
            "No source candidates found for this task. Try a more specific description or check the project files.",
            service.explainContext("fix checkout", emptyList()),
        )
        assertTrue(service.events().isEmpty())
    }

    fun testEmptyRelatedCodeDoesNotRequestAReviewPlan() {
        val service = IntelliJevProjectService(project)

        assertEquals(
            "No related-code candidates found for the selected fix. Try selecting code with distinctive identifiers.",
            service.explainBugs(emptyList()),
        )
        assertTrue(service.events().isEmpty())
    }
}
