package pi.ai.core.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.Context
import pi.ai.core.OPENAI_CODEX_PROVIDER
import pi.ai.core.TextContent
import pi.ai.core.Transport
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.getModel
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OpenAICodexWebSocketCachedTest {
    @Test
    fun `websocket cached transport sends continuation delta on reusable session`() {
        val sent = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val messageCount = AtomicInteger()
        val sessionId = "session-cached-${System.nanoTime()}"
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String,
                            ) {
                                sockets.addIfAbsent(webSocket)
                                sent += text
                                val label = if (messageCount.incrementAndGet() == 1) "first" else "second"
                                sendCompletedWebSocketResponse(webSocket, label)
                            }
                        },
                    ).build(),
            )

            val model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = server.url("/").toString())
            val options =
                OpenAICodexResponsesOptions(
                    apiKey = jwtWithAccount("acct_123"),
                    sessionId = sessionId,
                    transport = Transport.WEBSOCKET_CACHED,
                )
            val stream1 =
                streamOpenAICodexResponses(
                    model = model,
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("first"), 1L))),
                    options = options,
                )
            val first = (runBlocking { stream1.asFlow().toList() }.last() as AssistantMessageEvent.Done).message

            val stream2 =
                streamOpenAICodexResponses(
                    model = model,
                    context =
                        Context(
                            messages =
                                listOf(
                                    UserMessage(UserMessageContent.Text("first"), 1L),
                                    first,
                                    UserMessage(UserMessageContent.Text("second"), 2L),
                                ),
                        ),
                    options = options,
                )
            val second = (runBlocking { stream2.asFlow().toList() }.last() as AssistantMessageEvent.Done).message

            val secondPayload = Json.parseToJsonElement(sent[1]).jsonObject
            assertEquals(1, server.requestCount)
            assertEquals("resp_first", secondPayload["previous_response_id"]!!.jsonPrimitive.content)
            assertEquals(1, secondPayload["input"]!!.jsonArray.size)
            assertEquals("second", (second.content[0] as TextContent).text)
            sockets.forEach { it.close(1000, "test_done") }
        }
    }

    @Test
    fun `websocket cached transport does not fall back to SSE on connection failure`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .body("""{"error":{"message":"websocket unavailable"}}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .setHeader("content-type", "text/event-stream")
                    .body(
                        """
                        data: {"type":"response.output_item.added","item":{"type":"message","id":"msg_1","content":[]}}

                        data: {"type":"response.output_text.delta","delta":"sse"}

                        data: {"type":"response.output_item.done","item":{"type":"message","id":"msg_1","content":[{"type":"output_text","text":"sse"}]}}

                        data: {"type":"response.completed","response":{"id":"resp_sse","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

                        """.trimIndent(),
                    ).build(),
            )

            val stream =
                streamOpenAICodexResponses(
                    model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = server.url("/").toString()),
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L))),
                    options =
                        OpenAICodexResponsesOptions(
                            apiKey = jwtWithAccount("acct_123"),
                            sessionId = "session-cached-failure",
                            transport = Transport.WEBSOCKET_CACHED,
                        ),
                )
            val events = runBlocking { stream.asFlow().toList() }

            assertTrue(events.last() is AssistantMessageEvent.Error)
            assertEquals(1, server.requestCount)
        }
    }

    private fun jwtWithAccount(accountId: String): String {
        val payload = """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        return "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.sig"
    }

    private fun sendCompletedWebSocketResponse(
        webSocket: WebSocket,
        text: String,
    ) {
        webSocket.send("""{"type":"response.output_item.added","item":{"type":"message","id":"msg_$text","content":[]}}""")
        webSocket.send("""{"type":"response.output_text.delta","delta":"$text"}""")
        webSocket.send(
            """{"type":"response.output_item.done","item":{"type":"message","id":"msg_$text","content":[{"type":"output_text","text":"$text"}]}}""",
        )
        webSocket.send(
            """{"type":"response.completed","response":{"id":"resp_$text","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
        )
    }
}
