package pi.ai.core

private val overflowPatterns: List<Regex> =
    listOf(
        Regex("prompt is too long", RegexOption.IGNORE_CASE),
        Regex("request_too_large", RegexOption.IGNORE_CASE),
        Regex("input is too long for requested model", RegexOption.IGNORE_CASE),
        Regex("exceeds the context window", RegexOption.IGNORE_CASE),
        Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE),
        Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE),
        Regex("reduce the length of the messages", RegexOption.IGNORE_CASE),
        Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE),
        Regex("exceeds the limit of \\d+", RegexOption.IGNORE_CASE),
        Regex("exceeds the available context size", RegexOption.IGNORE_CASE),
        Regex("greater than the context length", RegexOption.IGNORE_CASE),
        Regex("context window exceeds limit", RegexOption.IGNORE_CASE),
        Regex("exceeded model token limit", RegexOption.IGNORE_CASE),
        Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE),
        Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE),
        Regex("prompt too long; exceeded (?:max )?context length", RegexOption.IGNORE_CASE),
        Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE),
        Regex("too many tokens", RegexOption.IGNORE_CASE),
        Regex("token limit exceeded", RegexOption.IGNORE_CASE),
        Regex("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", RegexOption.IGNORE_CASE),
    )

private val nonOverflowPatterns: List<Regex> =
    listOf(
        Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
        Regex("rate limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
    )

public fun isContextOverflow(
    message: AssistantMessage,
    contextWindow: Int? = null,
): Boolean {
    val errorMessage = message.errorMessage
    if (message.stopReason == StopReason.ERROR && !errorMessage.isNullOrBlank()) {
        val isNonOverflow = nonOverflowPatterns.any { it.containsMatchIn(errorMessage) }
        if (!isNonOverflow && overflowPatterns.any { it.containsMatchIn(errorMessage) }) {
            return true
        }
    }

    if (contextWindow != null && contextWindow > 0 && message.stopReason == StopReason.STOP) {
        val inputTokens = message.usage.input + message.usage.cacheRead
        if (inputTokens > contextWindow) {
            return true
        }
    }

    return false
}
