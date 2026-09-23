package dev.contextpacker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Provider profile changes must reach the actual Laya HTTP request on the next pack. */
class ProviderSettingsIntegrationTest : BasePlatformTestCase() {
    private fun <T> offEdt(block: suspend () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> {
            runBlocking { block() }
        }.get(60, TimeUnit.SECONDS)

    private fun fakeLaya(states: CopyOnWriteArrayList<String>, beforeReply: () -> Unit = {}): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/predict") { exchange ->
            val body = exchange.requestBody.use { it.readAllBytes().decodeToString() }
            states += Json.parseToJsonElement(body).jsonObject.getValue("state").jsonPrimitive.content
            beforeReply()
            val response = """{"answers":{"relevant":{"noul":0.8}},"usage":{"input_tokens":11}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/api/predict"
    }

    fun testNextPackUsesNewLayaEndpointAndExcerptWhileOtherTaskLimitsRemainIndependent() {
        myFixture.addFileToProject(
            "src/RetryPolicy.kt",
            "package demo\nclass RetryPolicy { fun retryBackoff() = 1 }\n" + "// source detail\n".repeat(80),
        )
        val firstStates = CopyOnWriteArrayList<String>()
        val secondStates = CopyOnWriteArrayList<String>()
        val (firstServer, firstEndpoint) = fakeLaya(firstStates)
        val (secondServer, secondEndpoint) = fakeLaya(secondStates)
        try {
            val settings = project.getService(PackerSettings::class.java)
            val service = project.getService(ContextPackerService::class.java)
            settings.applyPreferences(settings.getState().apply {
                laya = laya.copy(endpoint = firstEndpoint, model = "english", fullChars = 256)
            })
            val first = offEdt { service.pack("Update retry backoff", requestedProvider = DecisionProvider.LAYA) }
            assertEquals(DecisionProvider.LAYA, first.provider)
            assertTrue(firstStates.size >= 2)
            assertTrue(firstStates.any { it.length > "File: src/RetryPolicy.kt\n".length + 128 })
            assertTrue(secondStates.isEmpty())

            settings.applyPreferences(settings.getState().apply {
                laya = laya.copy(endpoint = secondEndpoint, fullChars = 128)
            })
            val second = offEdt { service.pack("Update retry backoff", requestedProvider = DecisionProvider.LAYA) }
            assertEquals(DecisionProvider.LAYA, second.provider)
            assertTrue(second.settingsRevision > first.settingsRevision)
            assertTrue(secondStates.size >= 2)
            assertTrue(secondStates.all { it.length <= "File: src/RetryPolicy.kt\n".length + 128 })
            assertEquals(first.jevCalls, firstStates.size)

            val callsBeforeRejectedTask = secondStates.size
            val rejected = offEdt {
                try {
                    service.pack("x".repeat(501), requestedProvider = DecisionProvider.LAYA)
                    null
                } catch (e: IllegalArgumentException) {
                    e
                }
            }
            assertNotNull(rejected)
            assertTrue(rejected!!.message.orEmpty().contains("500"))
            assertEquals(callsBeforeRejectedTask, secondStates.size)
            assertEquals(8_000, settings.configuration(DecisionProvider.KEYWORDS).taskLimit)
            assertEquals(8_000, settings.configuration(DecisionProvider.JEV).taskLimit)
            val keyword = offEdt { service.pack("x".repeat(501), requestedProvider = DecisionProvider.KEYWORDS) }
            assertEquals(DecisionProvider.KEYWORDS, keyword.provider)
            assertEquals(0, keyword.jevCalls)
            assertEquals(callsBeforeRejectedTask, secondStates.size)
        } finally {
            firstServer.stop(0)
            secondServer.stop(0)
        }
    }

    fun testProfileChangeDuringPackDoesNotPublishStaleResult() {
        myFixture.addFileToProject("src/RetryPolicy.kt", "class RetryPolicy { fun retryBackoff() = 1 }")
        val settings = project.getService(PackerSettings::class.java)
        val service = project.getService(ContextPackerService::class.java)
        val prior = offEdt { service.pack("Update retry backoff", requestedProvider = DecisionProvider.KEYWORDS) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (server, endpoint) = fakeLaya(CopyOnWriteArrayList()) {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        try {
            settings.applyPreferences(settings.getState().apply {
                laya = laya.copy(endpoint = endpoint, model = "english", fullChars = 128)
            })
            val revisionAtStart = settings.revision
            val future = ApplicationManager.getApplication().executeOnPooledThread<PackReport> {
                runBlocking { service.pack("Update retry backoff", requestedProvider = DecisionProvider.LAYA) }
            }
            assertTrue("Laya request did not reach the fake server", entered.await(10, TimeUnit.SECONDS))
            settings.applyPreferences(settings.getState().apply { laya = laya.copy(fullChars = 256) })
            release.countDown()
            val stale = future.get(30, TimeUnit.SECONDS)
            assertEquals(revisionAtStart, stale.settingsRevision)
            assertTrue(settings.revision > revisionAtStart)
            assertSame(prior, service.lastReport)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
