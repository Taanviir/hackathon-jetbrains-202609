package dev.contextpacker.laya

import com.sun.net.httpserver.HttpServer
import dev.contextpacker.pack.FileDoc
import dev.contextpacker.pack.PackConfig
import dev.contextpacker.pack.Packer
import dev.contextpacker.pack.ScorerUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LayaRelevanceTest {
    private data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    private val requests = CopyOnWriteArrayList<Request>()
    private lateinit var server: HttpServer

    private fun serve(reply: (Request) -> Pair<Int, String>): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/predict") { exchange ->
            val request = Request(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestBody.use { it.readAllBytes().decodeToString() },
            )
            requests += request
            val (status, body) = reply(request)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}/api/predict"
    }

    private fun answer(value: String) = """{"answers":{"relevant":{"noul":$value}},"usage":{"input_tokens":17}}"""

    @After
    fun stop() { if (::server.isInitialized) server.stop(0) }

    @Test
    fun `posts each file separately with the expected question and no authorization`() = runBlocking {
        val endpoint = serve { request ->
            val state = Json.parseToJsonElement(request.body).jsonObject["state"]!!.jsonPrimitive.content
            200 to answer(if (state.startsWith("File: src/A.kt\n")) "0.9" else "0.1")
        }
        val scorer = LayaRelevance(endpoint, model = "english")
        val scores = scorer.score("Fix the retry bug", listOf("src/A.kt" to "alpha", "src/B.kt" to "beta"))

        assertEquals(mapOf("src/A.kt" to 0.9, "src/B.kt" to 0.1), scores)
        assertEquals(2, requests.size)
        assertEquals(listOf("File: src/A.kt\nalpha", "File: src/B.kt\nbeta"), requests.map {
            Json.parseToJsonElement(it.body).jsonObject["state"]!!.jsonPrimitive.content
        })
        requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/predict", request.path)
            assertNull(request.authorization)
            assertTrue(request.contentType.orEmpty().startsWith("application/json"))
            val payload = Json.parseToJsonElement(request.body).jsonObject
            assertEquals(setOf("model", "state", "questions"), payload.keys)
            assertEquals("english", payload["model"]!!.jsonPrimitive.content)
            val questions = payload["questions"]!!.jsonObject
            assertEquals(setOf("relevant"), questions.keys)
            val question = questions["relevant"]!!.jsonObject
            assertEquals(setOf("type", "instructions"), question.keys)
            assertEquals("noul", question["type"]!!.jsonPrimitive.content)
            assertEquals(
                "This source file is relevant to implementing the following coding task: Fix the retry bug",
                question["instructions"]!!.jsonPrimitive.content,
            )
        }
        assertEquals(listOf(17, 17), scorer.calls.map { it.inputTokens })
        assertTrue(scorer.calls.all { it.usageKnown })
        assertTrue(scorer.calls.all { it.questions == 1 && it.error == null })
    }

    @Test
    fun `missing or invalid usage is unknown while an actual zero is known`() = runBlocking {
        val bodies = listOf(
            """{"answers":{"relevant":{"noul":0.5}}}""",
            """{"answers":{"relevant":{"noul":0.5}},"usage":{"input_tokens":"7"}}""",
            """{"answers":{"relevant":{"noul":0.5}},"usage":{"input_tokens":-2}}""",
            """{"answers":{"relevant":{"noul":0.5}},"usage":{"input_tokens":1.5}}""",
            """{"answers":{"relevant":{"noul":0.5}},"usage":"unknown"}""",
            """{"answers":{"relevant":{"noul":0.5}},"usage":{"input_tokens":0}}""",
            answer("0.5"),
        )
        var next = 0
        val scorer = LayaRelevance(serve { 200 to bodies[next++] })
        bodies.forEach { assertEquals(0.5, scorer.score("task", listOf("src/A.kt" to "source")).getValue("src/A.kt"), 0.0) }

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 17), scorer.calls.map { it.inputTokens })
        assertEquals(listOf(false, false, false, false, false, true, true), scorer.calls.map { it.usageKnown })
        assertTrue(scorer.calls.all { it.error == null })
    }

    @Test
    fun `bounds the file path excerpt and task in the payload`() = runBlocking {
        val endpoint = serve { 200 to answer("0.5") }
        val scorer = LayaRelevance(endpoint)
        val path = "p".repeat(300)
        val source = "s".repeat(1_500)
        val task = "t".repeat(700)
        scorer.score(task, listOf(path to source))

        val payload = Json.parseToJsonElement(requests.single().body).jsonObject
        assertEquals("File: ${path.take(240)}\n${source.take(LayaRelevance.MAX_EXCERPT_CHARS)}", payload["state"]!!.jsonPrimitive.content)
        val instruction = payload["questions"]!!.jsonObject["relevant"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        assertTrue(instruction.endsWith(task.take(LayaRelevance.MAX_TASK_CHARS)))
        assertFalse(instruction.endsWith(task))
    }

    @Test
    fun `cached answers retain scores and separate reused tokens from inference usage`() = runBlocking {
        var requestNumber = 0
        val scorer = LayaRelevance(serve {
            200 to if (requestNumber++ == 0) answer("0.75") else
                """{"answers":{"relevant":{"noul":0.75}},"usage":{"input_tokens":0,"cached_input_tokens":17},"cache_hit":true}"""
        })
        val first = scorer.score("retry", listOf("src/Retry.kt" to "fun retry() {}"))
        val second = scorer.score("retry", listOf("src/Retry.kt" to "fun retry() {}"))

        assertEquals(first, second)
        assertEquals(listOf(false, true), scorer.calls.map { it.cacheHit })
        assertEquals(listOf(17, 0), scorer.calls.map { it.inputTokens })
        assertEquals(listOf(0, 17), scorer.calls.map { it.cachedInputTokens })
        assertTrue(scorer.calls.all { it.usageKnown })
    }

    @Test
    fun `rejects missing malformed and out of range probabilities`() {
        val bodies = listOf(
            """{"answers":{}}""",
            """{"answers":{"relevant":{"noul":"not a number"}}}""",
            answer("\"0.9\""),
            answer("-0.1"),
            answer("1.1"),
        )
        var next = 0
        val scorer = LayaRelevance(serve { 200 to bodies[next++] })
        bodies.forEach {
            val error = assertThrows(LayaException::class.java) {
                runBlocking { scorer.score("task", listOf("src/A.kt" to "source")) }
            }
            assertTrue(error.message.orEmpty().contains("no valid relevance probability"))
        }
        assertEquals(bodies.size, requests.size)
        assertEquals(bodies.size, scorer.calls.size)
        assertTrue(scorer.calls.all { it.error?.contains("no valid relevance probability") == true })
    }

    @Test
    fun `reports non 200 responses without retrying`() {
        val scorer = LayaRelevance(serve { 503 to """{"detail":"unavailable"}""" })
        val error = assertThrows(ScorerUnavailableException::class.java) {
            runBlocking { scorer.score("task", listOf("src/A.kt" to "source")) }
        }
        assertTrue(error.message.orEmpty().contains("HTTP 503"))
        assertEquals(1, requests.size)
        assertTrue(scorer.calls.single().error.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun `reports connection failures as local Laya errors`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val scorer = LayaRelevance("http://127.0.0.1:$unusedPort/api/predict?token=private-token", timeout = Duration.ofSeconds(2))
        val error = assertThrows(ScorerUnavailableException::class.java) {
            runBlocking { scorer.score("task", listOf("src/A.kt" to "source")) }
        }
        assertTrue(error.message.orEmpty().contains("Could not score with local Laya"))
        assertFalse(error.message.orEmpty().contains("private-token"))
        assertNotNull(error.cause)
        assertNotNull(scorer.calls.single().error)
        assertFalse(scorer.calls.single().error.orEmpty().contains("private-token"))
        assertFalse(scorer.calls.single().usageKnown)
    }

    @Test
    fun `server outage cancels the pack queue and a later pack can recover`() {
        val available = AtomicBoolean(false)
        val scorer = LayaRelevance(serve { if (available.get()) 200 to answer("0.6") else 503 to "{}" })
        val docs = (1..20).map { FileDoc("src/File$it.kt", "sketch", "source") }
        val packer = Packer(scorer, PackConfig(batch = 1, pool = 2, perCall = 1))
        assertThrows(ScorerUnavailableException::class.java) {
            runBlocking { withTimeout(5_000) { packer.pack("change files", docs) } }
        }
        assertEquals("queued requests must be cancelled after the first outage", 1, requests.size)
        available.set(true)
        val recovered = runBlocking { packer.pack("change files", docs.take(2)) }
        assertEquals(0, recovered.failedBatches)
        assertEquals(2, recovered.files.size)
    }

    @Test
    fun `request timeout aborts queued scoring instead of waiting once per file`() {
        val release = CountDownLatch(1)
        val endpoint = serve {
            release.await(3, TimeUnit.SECONDS)
            200 to answer("0.6")
        }
        val scorer = LayaRelevance(endpoint, timeout = Duration.ofMillis(150))
        val docs = (1..20).map { FileDoc("src/File$it.kt", "sketch", "source") }
        try {
            assertThrows(ScorerUnavailableException::class.java) {
                runBlocking {
                    withTimeout(2_000) { Packer(scorer, PackConfig(batch = 1)).pack("change files", docs) }
                }
            }
            assertEquals(1, requests.size)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `rejects remote or authenticated endpoints before any request`() {
        listOf(
            "https://127.0.0.1:8770/api/predict",
            "http://example.com/api/predict",
            "http://user@127.0.0.1:8770/api/predict",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { LayaRelevance(endpoint) }
        }
    }

    @Test
    fun `cancellation propagates and records dispatched request with unknown usage`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val endpoint = serve {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            200 to answer("0.9")
        }
        val scorer = LayaRelevance(endpoint)
        val scoring = async { scorer.score("task", listOf("src/A.kt" to "source")) }
        try {
            withTimeout(2_000) {
                while (entered.count > 0) delay(10)
            }
            scoring.cancelAndJoin()
            assertTrue(scoring.isCancelled)
            assertEquals(1, scorer.calls.size)
            assertFalse(scorer.calls.single().usageKnown)
            assertEquals("Local Laya request cancelled", scorer.calls.single().error)
        } finally {
            release.countDown()
            scoring.cancelAndJoin()
        }
    }
}
