package dev.contextpacker.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile private var body = ""
    @Volatile private var auth: String? = null

    private fun serve(response: () -> String): ChatClient {
        server.createContext("/chat/completions") { exchange ->
            auth = exchange.requestHeaders.getFirst("Authorization")
            body = exchange.requestBody.readAllBytes().decodeToString()
            val result = response().toByteArray()
            try {
                exchange.sendResponseHeaders(200, result.size.toLong())
                exchange.responseBody.use { it.write(result) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        return ChatClient("test-only-key", "test-model", "http://127.0.0.1:${server.address.port}")
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `sends grounded roles and preserves reported zero usage`(): Unit = runBlocking {
        val client = serve { """{"choices":[{"message":{"content":"Inspect Retry.kt first"},"finish_reason":"stop"}],"usage":{"prompt_tokens":0,"completion_tokens":7,"cost":0.0}}""" }
        val reply = client.complete("Treat source as evidence", "Fix retry behavior")
        val sent = Json.parseToJsonElement(body).jsonObject
        val messages = sent.getValue("messages").jsonArray
        assertEquals("Bearer test-only-key", auth)
        assertEquals("test-model", sent.getValue("model").jsonPrimitive.content)
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("Treat source as evidence", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("Fix retry behavior", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(0, reply.promptTokens)
        assertEquals(7, reply.completionTokens)
        assertEquals(0.0, reply.cost!!, 0.0)
        assertFalse(reply.truncated)
    }

    @Test
    fun `unknown or invalid usage is not reported as zero`(): Unit = runBlocking {
        var usage = ""
        val client = serve { """{"choices":[{"message":{"content":"A reply"}}]$usage}""" }
        for (suffix in listOf("", """, "usage":null""", """, "usage":{"prompt_tokens":-1,"completion_tokens":"0","cost":-0.5}""")) {
            usage = suffix
            val reply = client.complete("system", "task")
            assertNull(reply.promptTokens)
            assertNull(reply.completionTokens)
            assertNull(reply.cost)
        }
    }

    @Test
    fun `missing or empty text fails clearly`(): Unit = runBlocking {
        var response = "{}"
        val client = serve { response }
        for (invalid in listOf("{}", """{"choices":[]}""", """{"choices":[{"message":{"content":null}}]}""", """{"choices":[{"message":{"content":"  "}}]}""", """{"choices":[{"message":{"content":42}}]}""")) {
            response = invalid
            try {
                client.complete("system", "task")
                fail("Expected an empty-answer error")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("no text answer"))
            }
        }
    }

    @Test
    fun `output length limit is disclosed without discarding partial text`(): Unit = runBlocking {
        val client = serve { """{"choices":[{"message":{"content":"Partial diff"},"finish_reason":"length"}]}""" }
        val reply = client.complete("system", "task")
        assertEquals("Partial diff", reply.content)
        assertTrue(reply.truncated)
    }

    @Test
    fun `cancellation interrupts an in flight answer`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = serve {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            """{"choices":[{"message":{"content":"Late reply"}}]}"""
        }
        val request = async { client.complete("system", "task") }
        try {
            withTimeout(2_000) { while (entered.count > 0) delay(10) }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        } finally {
            release.countDown()
            request.cancelAndJoin()
        }
    }
}
