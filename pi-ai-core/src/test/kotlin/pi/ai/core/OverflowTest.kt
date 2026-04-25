package pi.ai.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverflowTest {
    @Test
    fun `detects provider overflow errors`() {
        assertTrue(isContextOverflow(errorMessage("prompt is too long: 213462 tokens > 200000 maximum"), 200_000))
        assertTrue(isContextOverflow(errorMessage("""413 {"error":{"type":"request_too_large"}}"""), 200_000))
        assertTrue(isContextOverflow(errorMessage("Your input exceeds the context window of this model"), 128_000))
        assertTrue(isContextOverflow(errorMessage("context_length_exceeded"), 128_000))
    }

    @Test
    fun `does not classify rate limits as overflow`() {
        assertFalse(isContextOverflow(errorMessage("Throttling error: Too many tokens, please wait before trying again."), 200_000))
        assertFalse(isContextOverflow(errorMessage("rate limit: too many requests"), 200_000))
    }

    @Test
    fun `detects silent overflow from usage`() {
        val message =
            assistantMessage(
                stopReason = StopReason.STOP,
                usage = Usage(input = 210_000, cacheRead = 1, totalTokens = 210_001),
            )

        assertTrue(isContextOverflow(message, 200_000))
        assertFalse(isContextOverflow(message, 300_000))
    }

    private fun errorMessage(text: String): AssistantMessage =
        assistantMessage(
            stopReason = StopReason.ERROR,
            errorMessage = text,
        )

    private fun assistantMessage(
        stopReason: StopReason,
        errorMessage: String? = null,
        usage: Usage = Usage(),
    ): AssistantMessage =
        AssistantMessage(
            content = mutableListOf(TextContent("")),
            api = "test-api",
            provider = "test-provider",
            model = "test-model",
            usage = usage,
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = System.currentTimeMillis(),
        )
}
