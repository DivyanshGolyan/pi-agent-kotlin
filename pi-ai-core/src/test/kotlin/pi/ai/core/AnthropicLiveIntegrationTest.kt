package pi.ai.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("live")
class AnthropicLiveIntegrationTest {
    @Test
    fun `haiku live stream supports prompt caching`() =
        runTest {
            assumeTrue(apiKey().isNotBlank(), "ANTHROPIC_API_KEY is required for live tests")

            val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-haiku-4-5"))
            var firstPayload: kotlinx.serialization.json.JsonObject? = null
            val context =
                Context(
                    systemPrompt = largeCacheableSystemPrompt(),
                    messages =
                        listOf(
                            UserMessage(
                                content = UserMessageContent.Text("Reply with exactly CACHE-OK"),
                                timestamp = System.currentTimeMillis(),
                            ),
                        ),
                )

            val first =
                completeSimple(
                    model = model,
                    context = context,
                    options =
                        SimpleStreamOptions(
                            apiKey = apiKey(),
                            cacheRetention = CacheRetention.SHORT,
                            maxTokens = 64,
                            onPayload = { payload, _ ->
                                firstPayload = payload as kotlinx.serialization.json.JsonObject
                                payload
                            },
                        ),
                )

            val second =
                completeSimple(
                    model = model,
                    context = context,
                    options =
                        SimpleStreamOptions(
                            apiKey = apiKey(),
                            cacheRetention = CacheRetention.SHORT,
                            maxTokens = 64,
                        ),
                )

            val firstText = assistantText(first).trim()
            val secondText = assistantText(second).trim()

            assertTrue(firstText.contains("CACHE-OK"), "firstText=$firstText usage=${first.usage}")
            assertTrue(secondText.contains("CACHE-OK"), "secondText=$secondText usage=${second.usage}")
            assertTrue(
                firstPayload
                    ?.get("system")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.containsKey("cache_control") == true,
                "payload=$firstPayload firstUsage=${first.usage} secondUsage=${second.usage}",
            )
        }

    @Test
    fun `haiku live stream emits text deltas`() =
        runTest {
            assumeTrue(apiKey().isNotBlank(), "ANTHROPIC_API_KEY is required for live tests")

            val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-haiku-4-5"))
            val stream =
                streamSimple(
                    model = model,
                    context =
                        Context(
                            systemPrompt = "You are concise.",
                            messages =
                                listOf(
                                    UserMessage(
                                        content = UserMessageContent.Text("Reply with exactly STREAM-OK"),
                                        timestamp = System.currentTimeMillis(),
                                    ),
                                ),
                        ),
                    options =
                        SimpleStreamOptions(
                            apiKey = apiKey(),
                            maxTokens = 32,
                        ),
                )

            val events = stream.asFlow().toList()
            val result = stream.result()

            assertTrue(events.any { it is AssistantMessageEvent.TextDelta })
            assertTrue(assistantText(result).contains("STREAM-OK"))
        }

    @Test
    fun `haiku live supports image input`() =
        runTest {
            assumeTrue(apiKey().isNotBlank(), "ANTHROPIC_API_KEY is required for live tests")

            val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-haiku-4-5"))
            val result =
                completeSimple(
                    model = model,
                    context =
                        Context(
                            systemPrompt = "Answer with one lowercase color word only.",
                            messages =
                                listOf(
                                    UserMessage(
                                        content =
                                            UserMessageContent.Structured(
                                                listOf(
                                                    TextContent("What is the dominant color in this image?"),
                                                    ImageContent(
                                                        data = RED_SQUARE_PNG_BASE64,
                                                        mimeType = "image/png",
                                                    ),
                                                ),
                                            ),
                                        timestamp = System.currentTimeMillis(),
                                    ),
                                ),
                        ),
                    options =
                        SimpleStreamOptions(
                            apiKey = apiKey(),
                            maxTokens = 32,
                        ),
                )

            assertEquals(
                StopReason.STOP,
                result.stopReason,
                "error=${result.errorMessage} text=${assistantText(result)} content=${result.content}",
            )
            assertTrue(assistantText(result).lowercase().contains("red"), "text=${assistantText(result)} content=${result.content}")
        }

    @Test
    fun `sonnet live surfaces thinking blocks`() =
        runTest {
            assumeTrue(apiKey().isNotBlank(), "ANTHROPIC_API_KEY is required for live tests")

            val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-sonnet-4-6"))
            val result =
                completeSimple(
                    model = model,
                    context =
                        Context(
                            systemPrompt = "Think carefully and answer briefly.",
                            messages =
                                listOf(
                                    UserMessage(
                                        content =
                                            UserMessageContent.Text(
                                                "You are solving a constraint puzzle. There are five statements: " +
                                                    "(1) Exactly two of these five statements are true. " +
                                                    "(2) Statement 5 is false. " +
                                                    "(3) Statements 1 and 2 have the same truth value. " +
                                                    "(4) Exactly one of statements 2 and 3 is true. " +
                                                    "(5) Statement 4 is true. " +
                                                    "Determine which statements are true and then reply in the format TRUE:comma-separated-numbers.",
                                            ),
                                        timestamp = System.currentTimeMillis(),
                                    ),
                                ),
                        ),
                    options =
                        SimpleStreamOptions(
                            apiKey = apiKey(),
                            reasoning = ThinkingLevel.LOW,
                            maxTokens = 1024,
                        ),
                )

            assertTrue(
                result.content.any { it is ThinkingContent },
                "stopReason=${result.stopReason} text=${assistantText(result)} contentTypes=${result.content.map { it::class.simpleName }}",
            )
            assertTrue(
                result.stopReason != StopReason.ERROR && result.stopReason != StopReason.ABORTED,
                "stopReason=${result.stopReason} text=${assistantText(result)} content=${result.content}",
            )
        }

    private fun apiKey(): String = System.getenv("ANTHROPIC_API_KEY").orEmpty()
}

private fun assistantText(message: AssistantMessage): String =
    message.content
        .filterIsInstance<TextContent>()
        .joinToString(separator = "\n") { it.text }

private fun largeCacheableSystemPrompt(): String =
    buildString {
        append("You are validating prompt caching. ")
        repeat(500) {
            append("Return deterministic answers. ")
        }
    }

private const val RED_SQUARE_PNG_BASE64: String =
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAF0lEQVR4nGP8z0AaYCJR/aiGUQ1DSAMAQC4BH2bjRnMAAAAASUVORK5CYII="
