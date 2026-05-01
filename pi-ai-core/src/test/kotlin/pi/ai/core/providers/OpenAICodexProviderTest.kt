package pi.ai.core.providers

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AbortController
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.Context
import pi.ai.core.ImageContent
import pi.ai.core.OPENAI_CODEX_PROVIDER
import pi.ai.core.OPENAI_CODEX_RESPONSES_API
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ThinkingLevel
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Transport
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.getModel
import pi.ai.core.getProviders
import pi.ai.core.supportsXhigh
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OpenAICodexProviderTest {
    @Test
    fun `model registry includes upstream OpenAI Codex models`() {
        assertTrue(getProviders().contains(OPENAI_CODEX_PROVIDER))
        val model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!
        val gpt55 = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.5")!!
        assertEquals(OPENAI_CODEX_RESPONSES_API, model.api)
        assertEquals("https://chatgpt.com/backend-api", model.baseUrl)
        assertEquals(272_000, model.contextWindow)
        assertEquals(5.0, gpt55.cost.input)
        assertEquals(30.0, gpt55.cost.output)
        assertTrue(supportsXhigh(gpt55))
    }

    @Test
    fun `authorization flow uses upstream redirect and codex params`() {
        val flow = createOpenAICodexAuthorizationFlow()
        assertTrue(flow.url.startsWith(OPENAI_CODEX_AUTHORIZE_URL))
        assertTrue(flow.url.contains("client_id=$OPENAI_CODEX_CLIENT_ID"))
        assertTrue(flow.url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
        assertTrue(flow.url.contains("codex_cli_simplified_flow=true"))
        assertTrue(flow.url.contains("originator=pi"))
        assertTrue(flow.verifier.isNotBlank())
        assertEquals(32, flow.state.length)
    }

    @Test
    fun `account id is extracted from OpenAI OAuth claim`() {
        val token = jwtWithAccount("acct_123")
        assertEquals("acct_123", extractOpenAICodexAccountId(token))
    }

    @Test
    fun `authorization input parser accepts upstream manual forms`() {
        assertEquals(
            OpenAICodexAuthorizationInput(code = "code_1", state = "state_1"),
            parseOpenAICodexAuthorizationInput("$OPENAI_CODEX_REDIRECT_URI?code=code_1&state=state_1"),
        )
        assertEquals(
            OpenAICodexAuthorizationInput(code = "code_2", state = "state_2"),
            parseOpenAICodexAuthorizationInput("code_2#state_2"),
        )
        assertEquals(
            OpenAICodexAuthorizationInput(code = "code_3", state = "state_3"),
            parseOpenAICodexAuthorizationInput("code=code_3&state=state_3"),
        )
        assertEquals(OpenAICodexAuthorizationInput(code = "code_4"), parseOpenAICodexAuthorizationInput("code_4"))
    }

    @Test
    fun `OAuth provider exposes upstream Codex login shape and validates manual state`() {
        assertEquals(OPENAI_CODEX_PROVIDER, openAICodexOAuthProvider.id)
        assertEquals("ChatGPT Plus/Pro (Codex Subscription)", openAICodexOAuthProvider.name)
        assertTrue(openAICodexOAuthProvider.usesCallbackServer)

        var authUrl = ""
        var promptCalled = false
        val credentials =
            runBlocking {
                loginOpenAICodex(
                    callbacks =
                        OAuthLoginCallbacks(
                            onAuth = { authUrl = it.url },
                            onPrompt = {
                                promptCalled = true
                                ""
                            },
                            onManualCodeInput = {
                                val state = parseOpenAICodexAuthorizationInput(authUrl).state!!
                                "$OPENAI_CODEX_REDIRECT_URI?code=manual_code&state=$state"
                            },
                            callbackTimeoutMillis = 10_000,
                        ),
                    exchangeAuthorizationCode = { code, verifier ->
                        assertEquals("manual_code", code)
                        assertTrue(verifier.isNotBlank())
                        OpenAICodexOAuthCredentials(
                            access = jwtWithAccount("acct_123"),
                            refresh = "refresh",
                            expires = System.currentTimeMillis() + 60_000,
                            accountId = "acct_123",
                        )
                    },
                )
            }

        assertEquals("acct_123", credentials.accountId)
        assertFalse(promptCalled)
    }

    @Test
    fun `OAuth login rejects mismatched manual state and account-less exchanged token`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                loginOpenAICodex(
                    callbacks =
                        OAuthLoginCallbacks(
                            onAuth = {},
                            onPrompt = { "" },
                            onManualCodeInput = { "manual_code#wrong_state" },
                            callbackTimeoutMillis = 10_000,
                        ),
                    exchangeAuthorizationCode = { _, _ -> error("exchange should not run") },
                )
            }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                loginOpenAICodex(
                    callbacks =
                        OAuthLoginCallbacks(
                            onAuth = {},
                            onPrompt = { "manual_code" },
                            callbackTimeoutMillis = 1,
                        ),
                    exchangeAuthorizationCode = { _, _ ->
                        OpenAICodexOAuthCredentials(
                            access = jwtWithAccount(null),
                            refresh = "refresh",
                            expires = System.currentTimeMillis() + 60_000,
                            accountId = null,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `request payload matches Codex Responses shape`() {
        val payload =
            buildCodexRequestBody(
                model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!,
                context =
                    Context(
                        systemPrompt = "Be direct.",
                        messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L)),
                        tools = listOf(EchoTool),
                    ),
                options =
                    OpenAICodexResponsesOptions(
                        sessionId = "session-1",
                        reasoningEffort = ThinkingLevel.MINIMAL,
                        serviceTier = "priority",
                    ),
            )

        assertEquals("gpt-5.4-mini", payload["model"]!!.jsonPrimitive.content)
        assertEquals("false", payload["store"]!!.jsonPrimitive.content)
        assertEquals("true", payload["stream"]!!.jsonPrimitive.content)
        assertEquals("Be direct.", payload["instructions"]!!.jsonPrimitive.content)
        assertEquals("session-1", payload["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("low", payload["text"]!!.jsonObject["verbosity"]!!.jsonPrimitive.content)
        assertEquals("priority", payload["service_tier"]!!.jsonPrimitive.content)
        assertEquals("low", payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", payload["include"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "echo",
            payload["tools"]!!
                .jsonArray[0]
                .jsonObject["name"]!!
                .jsonPrimitive.content,
        )
        assertEquals(JsonNull, payload["tools"]!!.jsonArray[0].jsonObject["strict"])
    }

    @Test
    fun `request payload downgrades image only structured user message for text only Codex model`() {
        val payload =
            buildCodexRequestBody(
                model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.3-codex-spark")!!,
                context =
                    Context(
                        messages =
                            listOf(
                                UserMessage(
                                    UserMessageContent.Structured(listOf(ImageContent("abc", "image/png"))),
                                    1L,
                                ),
                            ),
                    ),
            )

        val content =
            payload["input"]!!
                .jsonArray[0]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject
        assertEquals("input_text", content["type"]!!.jsonPrimitive.content)
        assertEquals(NON_VISION_USER_IMAGE_PLACEHOLDER, content["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request payload maps image tool results as structured function output`() {
        val payload =
            buildCodexRequestBody(
                model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!,
                context =
                    Context(
                        messages =
                            listOf(
                                ToolResultMessage(
                                    toolCallId = "call_1|fc_1",
                                    toolName = "screenshot",
                                    content = listOf(TextContent("screen"), ImageContent("abc", "image/png")),
                                    isError = false,
                                    timestamp = 1L,
                                ),
                            ),
                    ),
            )

        val output =
            payload["input"]!!
                .jsonArray[0]
                .jsonObject["output"]!!
                .jsonArray
        assertEquals("input_text", output[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", output[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream posts upstream headers and parses text usage and response id`() {
        MockWebServer().use { server ->
            server.start()
            val model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = server.url("/").toString())
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .setHeader("content-type", "text/event-stream")
                    .body(
                        """
                        data: {"type":"response.created","response":{"id":"resp_1"}}

                        data: {"type":"response.output_item.added","item":{"type":"message","id":"msg_1","content":[]}}

                        data: {"type":"response.content_part.added","part":{"type":"output_text","text":""}}

                        data: {"type":"response.output_text.delta","delta":"hi"}

                        data: {"type":"response.output_item.done","item":{"type":"message","id":"msg_1","phase":"commentary","content":[{"type":"output_text","text":"hi"}]}}

                        data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","service_tier":"default","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12,"input_tokens_details":{"cached_tokens":3}}}}

                        """.trimIndent(),
                    ).build(),
            )

            val stream =
                streamOpenAICodexResponses(
                    model = model,
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L))),
                    options =
                        OpenAICodexResponsesOptions(
                            apiKey = jwtWithAccount("acct_123"),
                            sessionId = "session-1",
                            serviceTier = "priority",
                        ),
                )
            val events = runBlocking { stream.asFlow().toList() }
            val message = (events.last() as AssistantMessageEvent.Done).message
            val request = server.takeRequest()

            assertEquals("/codex/responses", request.url.encodedPath)
            assertEquals("Bearer ${jwtWithAccount("acct_123")}", request.headers["authorization"])
            assertEquals("acct_123", request.headers["chatgpt-account-id"])
            assertEquals("pi", request.headers["originator"])
            assertEquals("responses=experimental", request.headers["openai-beta"])
            assertEquals("session-1", request.headers["session_id"])
            assertEquals("session-1", request.headers["x-client-request-id"])
            assertEquals("hi", (message.content[0] as TextContent).text)
            assertTrue((message.content[0] as TextContent).textSignature!!.contains("commentary"))
            assertEquals("resp_1", message.responseId)
            assertEquals(7, message.usage.input)
            assertEquals(3, message.usage.cacheRead)
            assertTrue(message.usage.cost.total > 0)
            assertEquals(
                2.0 * (
                    model.cost.input / 1_000_000.0 * 7 +
                        model.cost.output / 1_000_000.0 * 2 +
                        model.cost.cacheRead / 1_000_000.0 * 3
                ),
                message.usage.cost.total,
                1e-12,
            )
            assertEquals(StopReason.STOP, message.stopReason)
        }
    }

    @Test
    fun `stream emits SSE deltas before response completes`() {
        val firstChunk =
            """
            data: {"type":"response.output_item.added","item":{"type":"message","id":"msg_1","content":[]}}

            data: {"type":"response.content_part.added","part":{"type":"output_text","text":""}}

            data: {"type":"response.output_text.delta","delta":"hi"}
            """.trimIndent() + "\n\n"
        val tail =
            """
            data: {"type":"response.output_item.done","item":{"type":"message","id":"msg_1","content":[{"type":"output_text","text":"hi"}]}}

            data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

            """.trimIndent()
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine().orEmpty().isNotEmpty()) {
                        // Drain request headers.
                    }
                    val output = socket.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.UTF_8),
                    )
                    output.write(firstChunk.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    Thread.sleep(3_000)
                    output.write(tail.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
            val abortController = AbortController()
            val stream =
                streamOpenAICodexResponses(
                    model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = "http://127.0.0.1:${server.localPort}"),
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L))),
                    options =
                        OpenAICodexResponsesOptions(
                            apiKey = jwtWithAccount("acct_123"),
                            signal = abortController.signal,
                        ),
                )

            val delta =
                runBlocking {
                    withTimeout(1_000) {
                        stream.asFlow().filterIsInstance<AssistantMessageEvent.TextDelta>().first()
                    }
                }

            assertEquals("hi", delta.delta)
            abortController.abort()
        }
    }

    @Test
    fun `stream retries transient Codex errors`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("""{"error":{"message":"service unavailable"}}""")
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

                        data: {"type":"response.output_text.delta","delta":"ok"}

                        data: {"type":"response.output_item.done","item":{"type":"message","id":"msg_1","content":[{"type":"output_text","text":"ok"}]}}

                        data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

                        """.trimIndent(),
                    ).build(),
            )

            val stream =
                streamOpenAICodexResponses(
                    model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = server.url("/").toString()),
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L))),
                    options = OpenAICodexResponsesOptions(apiKey = jwtWithAccount("acct_123")),
                )
            val events = runBlocking { stream.asFlow().toList() }
            val message = (events.last() as AssistantMessageEvent.Done).message

            assertEquals("ok", (message.content[0] as TextContent).text)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `stream supports Codex WebSocket transport`() {
        val received = LinkedBlockingQueue<String>()
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
                                received += text
                                webSocket.send(
                                    """{"type":"response.output_item.added","item":{"type":"message","id":"msg_1","content":[]}}""",
                                )
                                webSocket.send("""{"type":"response.output_text.delta","delta":"ws"}""")
                                webSocket.send(
                                    """{"type":"response.output_item.done","item":{"type":"message","id":"msg_1","content":[{"type":"output_text","text":"ws"}]}}""",
                                )
                                webSocket.send(
                                    """{"type":"response.completed","response":{"id":"resp_ws","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
                                )
                            }
                        },
                    ).build(),
            )

            val stream =
                streamOpenAICodexResponses(
                    model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = server.url("/").toString()),
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L))),
                    options =
                        OpenAICodexResponsesOptions(
                            apiKey = jwtWithAccount("acct_123"),
                            sessionId = "session-1",
                            transport = Transport.WEBSOCKET,
                        ),
                )
            val events = runBlocking { stream.asFlow().toList() }
            val message = (events.last() as AssistantMessageEvent.Done).message
            val request = server.takeRequest()
            val sent = received.poll(1, TimeUnit.SECONDS)!!

            assertEquals("/codex/responses", request.url.encodedPath)
            assertEquals("responses_websockets=2026-02-06", request.headers["openai-beta"])
            assertEquals("session-1", request.headers["session_id"])
            assertEquals("session-1", request.headers["x-client-request-id"])
            assertNull(request.headers["accept"])
            assertTrue(sent.contains("response.create"))
            assertEquals("ws", (message.content[0] as TextContent).text)
            assertEquals("resp_ws", message.responseId)
        }
    }

    @Test
    fun `busy Codex WebSocket session uses temporary socket without replacing cache`() {
        val received = LinkedBlockingQueue<String>()
        val releaseFirst = CountDownLatch(1)
        val firstMessages = AtomicInteger()
        val secondMessages = AtomicInteger()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val sessionId = "session-busy-${System.nanoTime()}"
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
                                val count = firstMessages.incrementAndGet()
                                received += "first:$count"
                                if (count == 1) {
                                    thread(isDaemon = true) {
                                        releaseFirst.await(5, TimeUnit.SECONDS)
                                        sendCompletedWebSocketResponse(webSocket, "first")
                                    }
                                } else {
                                    sendCompletedWebSocketResponse(webSocket, "third-first")
                                }
                            }
                        },
                    ).build(),
            )
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
                                val count = secondMessages.incrementAndGet()
                                received += "second:$count"
                                sendCompletedWebSocketResponse(webSocket, "second")
                            }
                        },
                    ).build(),
            )

            val model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!.copy(baseUrl = server.url("/").toString())
            val options =
                OpenAICodexResponsesOptions(
                    apiKey = jwtWithAccount("acct_123"),
                    sessionId = sessionId,
                    transport = Transport.WEBSOCKET,
                )
            val stream1 =
                streamOpenAICodexResponses(
                    model = model,
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("first"), 1L))),
                    options = options,
                )
            assertEquals("first:1", received.poll(1, TimeUnit.SECONDS))

            val stream2 =
                streamOpenAICodexResponses(
                    model = model,
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("second"), 2L))),
                    options = options,
                )
            runBlocking { stream2.asFlow().toList() }
            assertEquals("second:1", received.poll(1, TimeUnit.SECONDS))

            releaseFirst.countDown()
            runBlocking { stream1.asFlow().toList() }

            val stream3 =
                streamOpenAICodexResponses(
                    model = model,
                    context = Context(messages = listOf(UserMessage(UserMessageContent.Text("third"), 3L))),
                    options = options,
                )
            val events3 = runBlocking { stream3.asFlow().toList() }
            val message3 = (events3.last() as AssistantMessageEvent.Done).message

            assertEquals("first:2", received.poll(1, TimeUnit.SECONDS))
            assertEquals("third-first", (message3.content[0] as TextContent).text)
            assertEquals(2, server.requestCount)
            sockets.forEach { it.close(1000, "test_done") }
        }
    }

    @Test
    fun `request payload normalizes resumed Responses history`() {
        val payload =
            buildCodexRequestBody(
                model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!,
                context =
                    Context(
                        messages =
                            listOf(
                                AssistantMessage(
                                    content = mutableListOf(TextContent("partial")),
                                    api = OPENAI_CODEX_RESPONSES_API,
                                    provider = OPENAI_CODEX_PROVIDER,
                                    model = "gpt-5.4-mini",
                                    usage = Usage(),
                                    stopReason = StopReason.ERROR,
                                    timestamp = 1L,
                                ),
                                AssistantMessage(
                                    content =
                                        mutableListOf(
                                            ThinkingContent("foreign thought", thinkingSignature = """{"type":"reasoning"}"""),
                                            ToolCall("call.bad|foreign-item", "echo", buildJsonObject { put("value", "x") }),
                                        ),
                                    api = "anthropic-messages",
                                    provider = "anthropic",
                                    model = "claude",
                                    usage = Usage(),
                                    stopReason = StopReason.TOOL_USE,
                                    timestamp = 2L,
                                ),
                                UserMessage(UserMessageContent.Text("continue"), 3L),
                            ),
                    ),
            )

        val input = payload["input"]!!.jsonArray
        assertEquals("message", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "foreign thought",
            input[0]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        assertEquals("function_call", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        val normalizedToolCallId = input[1].jsonObject["call_id"]!!.jsonPrimitive.content
        assertEquals("function_call_output", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(normalizedToolCallId, input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("user", input[3].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request payload preserves Codex text signature phase`() {
        val payload =
            buildCodexRequestBody(
                model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!,
                context =
                    Context(
                        messages =
                            listOf(
                                AssistantMessage(
                                    content = mutableListOf(TextContent("status", """{"v":1,"id":"msg_1","phase":"commentary"}""")),
                                    api = OPENAI_CODEX_RESPONSES_API,
                                    provider = OPENAI_CODEX_PROVIDER,
                                    model = "gpt-5.4-mini",
                                    usage = Usage(),
                                    stopReason = StopReason.STOP,
                                    timestamp = 1L,
                                ),
                            ),
                    ),
            )

        val message = payload["input"]!!.jsonArray[0].jsonObject
        assertEquals("msg_1", message["id"]!!.jsonPrimitive.content)
        assertEquals("commentary", message["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream fails closed when token has no account id`() {
        val stream =
            streamOpenAICodexResponses(
                model = getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!,
                context = Context(messages = listOf(UserMessage(UserMessageContent.Text("hello"), 1L))),
                options = OpenAICodexResponsesOptions(apiKey = jwtWithAccount(null)),
            )
        val events = runBlocking { stream.asFlow().toList() }
        val error = events.last() as AssistantMessageEvent.Error
        assertEquals(StopReason.ERROR, error.reason)
        assertTrue(error.error.errorMessage!!.contains("accountId"))
    }

    private fun jwtWithAccount(accountId: String?): String {
        val payload =
            if (accountId == null) {
                "{}"
            } else {
                """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
            }
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

    private object EchoTool : Tool<Unit> {
        override val name: String = "echo"
        override val description: String = "Echo input"
        override val parameters = buildJsonObject { put("type", "object") }

        override fun validateArguments(arguments: kotlinx.serialization.json.JsonObject): Unit = Unit
    }
}
