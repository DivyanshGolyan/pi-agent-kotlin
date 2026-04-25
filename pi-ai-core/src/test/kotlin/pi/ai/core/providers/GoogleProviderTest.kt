package pi.ai.core.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.Context
import pi.ai.core.GOOGLE_GENERATIVE_AI_API
import pi.ai.core.GOOGLE_PROVIDER
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingBudgets
import pi.ai.core.ThinkingContent
import pi.ai.core.ThinkingLevel
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.getModel
import pi.ai.core.getProviders

class GoogleProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildGoogleParams maps prompt tools and disabled thinking`() {
        val payload =
            buildGoogleParams(
                model = createGoogleModel(),
                context =
                    Context(
                        systemPrompt = "Be brief.",
                        messages = listOf(UserMessage(UserMessageContent.Text("Hello"), 1L)),
                        tools = listOf(EchoTool),
                    ),
                options =
                    GoogleOptions(
                        temperature = 0.2,
                        maxTokens = 128,
                        thinking = GoogleThinkingOptions(enabled = false),
                        toolChoice = GoogleToolChoice.ANY,
                    ),
            )

        val generationConfig = payload["generationConfig"]!!.jsonObject
        assertEquals("0.2", generationConfig["temperature"]!!.jsonPrimitive.content)
        assertEquals("128", generationConfig["maxOutputTokens"]!!.jsonPrimitive.content)
        assertEquals(
            "Be brief.",
            payload["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "ANY",
            payload["toolConfig"]!!
                .jsonObject["functionCallingConfig"]!!
                .jsonObject["mode"]!!
                .jsonPrimitive.content,
        )
        assertEquals("0", generationConfig["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        assertEquals(
            "echo",
            payload["tools"]!!
                .jsonArray[0]
                .jsonObject["functionDeclarations"]!!
                .jsonArray[0]
                .jsonObject["name"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `streamSimpleGoogle maps reasoning budgets and levels`() {
        val flashPayload =
            captureSimplePayload(
                model = createGoogleModel(id = "gemini-2.5-flash"),
                options =
                    SimpleStreamOptions(
                        apiKey = "key",
                        reasoning = ThinkingLevel.MEDIUM,
                        thinkingBudgets = ThinkingBudgets(medium = 1234),
                    ),
            )
        assertEquals(
            "1234",
            flashPayload["generationConfig"]!!
                .jsonObject["thinkingConfig"]!!
                .jsonObject["thinkingBudget"]!!
                .jsonPrimitive.content,
        )

        val gemini3Payload =
            captureSimplePayload(
                model = createGoogleModel(id = "gemini-3-pro-preview"),
                options = SimpleStreamOptions(apiKey = "key", reasoning = ThinkingLevel.XHIGH),
            )
        assertEquals(
            "HIGH",
            gemini3Payload["generationConfig"]!!
                .jsonObject["thinkingConfig"]!!
                .jsonObject["thinkingLevel"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `convertGoogleMessages preserves only valid same-model thought signatures`() {
        val model = createGoogleModel(id = "gemini-3-pro-preview")
        val contents =
            convertGoogleMessages(
                model,
                Context(
                    messages =
                        listOf(
                            AssistantMessage(
                                content =
                                    mutableListOf(
                                        ThinkingContent("same", thinkingSignature = "QUJDRA=="),
                                        ToolCall("tool.1", "echo", buildJsonObject { put("value", "x") }),
                                    ),
                                api = GOOGLE_GENERATIVE_AI_API,
                                provider = GOOGLE_PROVIDER,
                                model = "gemini-3-pro-preview",
                                usage = Usage(),
                                stopReason = StopReason.STOP,
                                timestamp = 1L,
                            ),
                            AssistantMessage(
                                content = mutableListOf(ThinkingContent("foreign", thinkingSignature = "not-base64")),
                                api = "anthropic-messages",
                                provider = "anthropic",
                                model = "claude",
                                usage = Usage(),
                                stopReason = StopReason.STOP,
                                timestamp = 2L,
                            ),
                        ),
                ),
            )

        val firstParts = contents[0].jsonObject["parts"]!!.jsonArray
        assertEquals("QUJDRA==", firstParts[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("skip_thought_signature_validator", firstParts[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)

        val secondPart = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertFalse(secondPart.containsKey("thoughtSignature"))
        assertFalse(secondPart.containsKey("thought"))
    }

    @Test
    fun `convertGoogleMessages filters images for text-only models and maps tool result images`() {
        val textOnly =
            convertGoogleMessages(
                createGoogleModel(input = setOf(InputModality.TEXT)),
                Context(
                    messages =
                        listOf(
                            UserMessage(
                                UserMessageContent.Structured(
                                    listOf(ImageContent(data = "abc", mimeType = "image/png")),
                                ),
                                1L,
                            ),
                        ),
                ),
            )
        assertEquals(0, textOnly.size)

        val gemini3ToolResult =
            convertGoogleMessages(
                createGoogleModel(id = "gemini-3-pro-preview"),
                Context(
                    messages =
                        listOf(
                            ToolResultMessage(
                                toolCallId = "tool-1",
                                toolName = "screenshot",
                                content = listOf(ImageContent(data = "abc", mimeType = "image/png")),
                                isError = false,
                                timestamp = 2L,
                            ),
                        ),
                ),
            )
        val functionResponse =
            gemini3ToolResult[0]
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["functionResponse"]!!
                .jsonObject
        assertNotNull(functionResponse["parts"])
    }

    @Test
    fun `handleGoogleStreamChunk defaults missing function args to empty object`() =
        runTest {
            val stream = pi.ai.core.AssistantMessageEventStream()
            val output =
                AssistantMessage(
                    content = mutableListOf(),
                    api = GOOGLE_GENERATIVE_AI_API,
                    provider = GOOGLE_PROVIDER,
                    model = "gemini-2.5-flash",
                    usage = Usage(),
                    stopReason = StopReason.STOP,
                    timestamp = 1L,
                )

            handleGoogleStreamChunk(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"echo"}}]},"finishReason":"STOP"}]}""",
                output,
                stream,
                createGoogleModel(),
            )

            val toolCall = output.content.single() as ToolCall
            assertEquals("echo", toolCall.name)
            assertEquals(0, toolCall.arguments.size)
            assertEquals(StopReason.TOOL_USE, output.stopReason)
        }

    @Test
    fun `streamGoogle reads SSE text usage response id and request payload`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("content-type", "text/event-stream")
                        .body(
                            """
                            data: {"responseId":"resp-1","candidates":[{"content":{"parts":[{"text":"hel"}]}}],"usageMetadata":{"promptTokenCount":10,"cachedContentTokenCount":2,"candidatesTokenCount":3,"thoughtsTokenCount":4,"totalTokenCount":17}}
                            
                            data: {"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}]}
                            
                            """.trimIndent(),
                        ).build(),
                )

                val model = createGoogleModel(baseUrl = server.url("/v1beta").toString().trimEnd('/'))
                val stream =
                    streamGoogle(
                        model,
                        Context(messages = listOf(UserMessage(UserMessageContent.Text("Hi"), 1L))),
                        GoogleOptions(apiKey = "gemini-key"),
                    )
                val events = stream.asFlow().toList()
                val result = stream.result()
                val request = server.takeRequest()
                val requestBody = json.parseToJsonElement(request.body!!.utf8()).jsonObject

                assertEquals(
                    "/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
                    request.url.encodedPath + "?" + request.url.encodedQuery,
                )
                assertEquals("gemini-key", request.headers["x-goog-api-key"])
                assertEquals(
                    "Hi",
                    requestBody["contents"]!!
                        .jsonArray[0]
                        .jsonObject["parts"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                )
                assertEquals("hello", (result.content.single() as TextContent).text)
                assertEquals("resp-1", result.responseId)
                assertEquals(8, result.usage.input)
                assertEquals(7, result.usage.output)
                assertEquals(17, result.usage.totalTokens)
                assertTrue(events.any { it is AssistantMessageEvent.TextDelta })
                assertEquals(StopReason.STOP, result.stopReason)
            }
        }

    @Test
    fun `google models are registered for generic stream resolution`() {
        val model = requireNotNull(getModel(GOOGLE_PROVIDER, "gemini-2.5-flash"))

        assertTrue(getProviders().contains(GOOGLE_PROVIDER))
        assertEquals(GOOGLE_GENERATIVE_AI_API, model.api)
        assertEquals(GOOGLE_PROVIDER, model.provider)
        assertTrue(model.reasoning)
        assertTrue(model.input.contains(InputModality.IMAGE))
    }

    private fun captureSimplePayload(
        model: Model<String>,
        options: SimpleStreamOptions,
    ): JsonObject {
        var captured: JsonObject? = null
        val stream =
            streamSimpleGoogle(
                model,
                Context(messages = listOf(UserMessage(UserMessageContent.Text("Hi"), 1L))),
                options.copy(
                    onPayload = { payload, _ ->
                        captured = payload as JsonObject
                        error("captured")
                    },
                ),
            )
        runBlocking { stream.result() }
        return requireNotNull(captured)
    }

    private object EchoTool : Tool<JsonObject> {
        override val name: String = "echo"
        override val description: String = "Echo input"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }

        override fun validateArguments(arguments: JsonObject): JsonObject = arguments
    }
}

private fun createGoogleModel(
    id: String = "gemini-2.5-flash",
    baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    input: Set<InputModality> = setOf(InputModality.TEXT, InputModality.IMAGE),
): Model<String> =
    Model(
        id = id,
        name = id,
        api = GOOGLE_GENERATIVE_AI_API,
        provider = GOOGLE_PROVIDER,
        baseUrl = baseUrl,
        reasoning = true,
        input = input,
        cost = ModelCost(1.0, 2.0, 0.5, 0.25),
        contextWindow = 1_048_576,
        maxTokens = 65_536,
    )
