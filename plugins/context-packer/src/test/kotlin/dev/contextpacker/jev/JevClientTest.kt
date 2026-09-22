package dev.contextpacker.jev

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JevClientTest {
    private val requests = CopyOnWriteArrayList<Pair<String?, String>>()
    private val headers = CopyOnWriteArrayList<com.sun.net.httpserver.Headers>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Serves each status in turn, then the recorded fixture. Returns the endpoint URL. */
    private fun serve(
        vararg statuses: Int,
        retryAfterMs: String? = null,
        path: String = "/v1/systemone",
        fixtureName: String = "systemone_noul.json",
        responseBodies: List<String> = emptyList(),
    ): String {
        val fixture = javaClass.getResource("/fixtures/$fixtureName")!!.readText()
        val hits = AtomicInteger()
        server.createContext(path) { ex ->
            headers += ex.requestHeaders
            requests += ex.requestHeaders.getFirst("Authorization") to ex.requestBody.readAllBytes().decodeToString()
            val index = hits.getAndIncrement()
            val status = statuses.getOrNull(index) ?: 200
            val body = (responseBodies.getOrNull(index) ?: if (status == 200) fixture else """{"detail":"nope"}""").toByteArray()
            retryAfterMs?.let { ex.responseHeaders.add("retry-after-ms", it) }
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}$path"
    }

    @After
    fun stop() = server.stop(0)

    private val state = buildJsonObject { put("task", "Add exponential backoff to retries") }
    private val questions = mapOf("f000" to Questions.noul("q0"), "f001" to Questions.noul("q1"))

    @Test
    fun `sends the SDK's body and bearer auth, parses answers and usage`() = runBlocking {
        val client = JevClient("k3y", endpoint = serve())
        val response = client.systemOne(state, questions)

        assertEquals(0.96, response.noul("f000")!!, 1e-9)
        assertEquals(0.03, response.noul("f001")!!, 1e-9)
        assertNull(response.noul("missing"))
        assertEquals(400, response.inputTokens)
        assertTrue(response.usageKnown)
        assertEquals("jev-1.13.0", response.model)

        val (auth, body) = requests.single()
        assertEquals("Bearer k3y", auth)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("jev-latest", sent["model"]!!.jsonPrimitive.content)
        assertEquals(setOf("f000", "f001"), sent["questions"]!!.jsonObject.keys)
        assertEquals("Add exponential backoff to retries", sent["state"]!!.jsonObject["task"]!!.jsonPrimitive.content)
        assertNull(client.calls.single().error)
        assertTrue(client.calls.single().usageKnown)
    }

    @Test
    fun `distinguishes absent or invalid token usage from a reported zero`() = runBlocking {
        val answer = """{"answers":{"f000":{"noul":0.96},"f001":{"noul":0.03}}"""
        val responses = listOf(
            "$answer}",
            """$answer,"usage":{"input_tokens":"7"}}""",
            """$answer,"usage":{"input_tokens":-3}}""",
            """$answer,"usage":{"input_tokens":1.5}}""",
            """$answer,"usage":"unknown"}""",
            """$answer,"usage":{"input_tokens":0}}""",
            """$answer,"usage":{"inputTokens":9}}""",
        )
        val client = JevClient("k", endpoint = serve(responseBodies = responses))
        val result = responses.map { client.systemOne(state, questions) }

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 9), result.map { it.inputTokens })
        assertEquals(listOf(false, false, false, false, false, true, true), result.map { it.usageKnown })
        assertEquals(result.map { it.inputTokens }, client.calls.map { it.inputTokens })
        assertEquals(result.map { it.usageKnown }, client.calls.map { it.usageKnown })
    }

    @Test
    fun `retries 429 and 5xx, honouring retry-after-ms`() = runBlocking {
        val client = JevClient("k", endpoint = serve(429, 503, retryAfterMs = "10", responseBodies = listOf(
            """{"detail":"retry","usage":{"input_tokens":5}}""",
            """{"detail":"retry"}""",
        )))
        val started = System.nanoTime()
        assertEquals(0.96, client.systemOne(state, questions).noul("f000")!!, 1e-9)
        assertEquals(3, requests.size)
        assertEquals(listOf(5, 0, 400), client.calls.map { it.inputTokens })
        assertEquals(listOf(true, false, true), client.calls.map { it.usageKnown })
        assertEquals(listOf(true, true, false), client.calls.map { it.error != null })
        assertTrue("used retry-after-ms, not the 0.5 s backoff", (System.nanoTime() - started) / 1_000_000 < 400)
    }

    @Test
    fun `records malformed HTTP 200 with known or unknown usage`() = runBlocking {
        val client = JevClient("k", endpoint = serve(responseBodies = listOf(
            """{"usage":{"input_tokens":17},"answers":[]}""",
            """{"usage": """,
        )))
        repeat(2) {
            try {
                client.systemOne(state, questions)
                fail("expected malformed response to throw")
            } catch (_: Exception) {
                // Parsing must still fail; only the ledger behavior changes.
            }
        }
        assertEquals(listOf(17, 0), client.calls.map { it.inputTokens })
        assertEquals(listOf(true, false), client.calls.map { it.usageKnown })
        assertTrue(client.calls.all { it.error != null })
    }

    @Test
    fun `records a dispatched IO failure`() = runBlocking {
        server.createContext("/drop") { ex ->
            ex.requestBody.readAllBytes()
            ex.close()
        }
        server.start()
        val client = JevClient("k", endpoint = "http://127.0.0.1:${server.address.port}/drop", maxRetries = 0)
        try {
            client.systemOne(state, questions)
            fail("expected transport failure")
        } catch (_: Exception) {
            val call = client.calls.single()
            assertEquals(0, call.inputTokens)
            assertTrue(!call.usageKnown)
            assertNotNull(call.error)
        }
    }

    @Test
    fun `records an in flight cancellation`() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/slow") { ex ->
            ex.requestBody.readAllBytes()
            received.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { ex.sendResponseHeaders(200, -1); ex.close() }
        }
        server.start()
        val client = JevClient("k", endpoint = "http://127.0.0.1:${server.address.port}/slow")
        try {
            val job = launch(Dispatchers.IO) { client.systemOne(state, questions) }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            val call = client.calls.single()
            assertEquals("Jev request cancelled", call.error)
            assertTrue(!call.usageKnown)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `negative retry-after falls back to bounded backoff`() = runBlocking {
        val client = JevClient("k", endpoint = serve(429, retryAfterMs = "-5"))
        val started = System.nanoTime()
        client.systemOne(state, questions)
        assertTrue("negative header must not become a zero delay", (System.nanoTime() - started) / 1_000_000 >= 300)
    }

    @Test
    fun `nonfinite retry-after falls back to bounded backoff`() = runBlocking {
        val client = JevClient("k", endpoint = serve(429, retryAfterMs = "NaN"))
        val started = System.nanoTime()
        client.systemOne(state, questions)
        assertTrue("nonfinite header must not become a zero delay", (System.nanoTime() - started) / 1_000_000 >= 300)
    }

    @Test
    fun `does not retry a 400 and reports it`() = runBlocking {
        val client = JevClient("k", endpoint = serve(400))
        try {
            client.systemOne(state, questions)
            fail("expected JevException")
        } catch (e: JevException) {
            assertEquals(400, e.status)
        }
        assertEquals(1, requests.size)
        assertTrue(client.calls.single().error!!.contains("(HTTP 400)"))
        assertTrue(!client.calls.single().usageKnown)
    }

    @Test
    fun `gives up after max retries`() = runBlocking {
        val client = JevClient("k", endpoint = serve(500, 500, 500), maxRetries = 2)
        try {
            client.systemOne(state, questions)
            fail("expected JevException")
        } catch (e: JevException) {
            assertEquals(500, e.status)
        }
        assertEquals(3, requests.size)
    }

    @Test
    fun `gateway backend sends the AI SDK envelope and reads boolean probabilities`() = runBlocking {
        val url = serve(path = "/v4/ai/evaluation-model", fixtureName = "gateway_boolean.json")
        val client = JevClient("gw", JevBackend.GATEWAY, endpoint = url)
        val response = client.systemOne(state, questions)

        assertEquals(0.96, response.noul("f000")!!, 1e-9)
        assertEquals(0.03, response.noul("f001")!!, 1e-9)
        assertEquals(400, response.inputTokens)
        assertEquals("typesafe-ai/jev", response.model)

        val h = headers.single()
        assertEquals("Bearer gw", h.getFirst("Authorization"))
        assertEquals("typesafe-ai/jev", h.getFirst("ai-model-id"))
        assertEquals("4", h.getFirst("ai-evaluation-model-specification-version"))
        assertEquals("0.0.1", h.getFirst("ai-gateway-protocol-version"))
        assertEquals("api-key", h.getFirst("ai-gateway-auth-method"))
        val sent = Json.parseToJsonElement(requests.single().second).jsonObject
        assertNull("the model travels in a header, not the body", sent["model"])
        assertEquals("boolean", sent["questions"]!!.jsonObject["f000"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
