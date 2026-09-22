package dev.contextpacker.jev

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class JevClientTest {
    private val fixture = javaClass.getResource("/fixtures/systemone_noul.json")!!.readText()
    private val requests = CopyOnWriteArrayList<Pair<String?, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Serves each status in turn, then the recorded fixture. */
    private fun serve(vararg statuses: Int, retryAfterMs: String? = null): String {
        val hits = AtomicInteger()
        server.createContext("/v1/systemone") { ex ->
            requests += ex.requestHeaders.getFirst("Authorization") to ex.requestBody.readAllBytes().decodeToString()
            val status = statuses.getOrNull(hits.getAndIncrement()) ?: 200
            val body = (if (status == 200) fixture else """{"detail":"nope"}""").toByteArray()
            retryAfterMs?.let { ex.responseHeaders.add("retry-after-ms", it) }
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    private val state = buildJsonObject { put("task", "Add exponential backoff to retries") }
    private val questions = mapOf("f000" to Questions.noul("q0"), "f001" to Questions.noul("q1"))

    @Test
    fun `sends the SDK's body and bearer auth, parses answers and usage`() = runBlocking {
        val client = JevClient("k3y", baseUrl = serve())
        val response = client.systemOne(state, questions)

        assertEquals(0.96, response.noul("f000")!!, 1e-9)
        assertEquals(0.03, response.noul("f001")!!, 1e-9)
        assertNull(response.noul("missing"))
        assertEquals(400, response.inputTokens)
        assertEquals("jev-1.13.0", response.model)

        val (auth, body) = requests.single()
        assertEquals("Bearer k3y", auth)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("jev-latest", sent["model"]!!.jsonPrimitive.content)
        assertEquals(setOf("f000", "f001"), sent["questions"]!!.jsonObject.keys)
        assertEquals("Add exponential backoff to retries", sent["state"]!!.jsonObject["task"]!!.jsonPrimitive.content)
        assertNull(client.calls.single().error)
    }

    @Test
    fun `retries 429 and 5xx, honouring retry-after-ms`() = runBlocking {
        val client = JevClient("k", baseUrl = serve(429, 503, retryAfterMs = "10"))
        val started = System.nanoTime()
        assertEquals(0.96, client.systemOne(state, questions).noul("f000")!!, 1e-9)
        assertEquals(3, requests.size)
        assertTrue("used retry-after-ms, not the 0.5 s backoff", (System.nanoTime() - started) / 1_000_000 < 400)
    }

    @Test
    fun `does not retry a 400 and reports it`() = runBlocking {
        val client = JevClient("k", baseUrl = serve(400))
        try {
            client.systemOne(state, questions)
            fail("expected JevException")
        } catch (e: JevException) {
            assertEquals(400, e.status)
        }
        assertEquals(1, requests.size)
        assertTrue(client.calls.single().error!!.startsWith("HTTP 400"))
    }

    @Test
    fun `gives up after max retries`() = runBlocking {
        val client = JevClient("k", baseUrl = serve(500, 500, 500), maxRetries = 2)
        try {
            client.systemOne(state, questions)
            fail("expected JevException")
        } catch (e: JevException) {
            assertEquals(500, e.status)
        }
        assertEquals(3, requests.size)
    }
}
