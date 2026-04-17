package pi.ai.core.providers

import pi.ai.core.Model
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StreamOptions
import pi.ai.core.ThinkingBudgets
import pi.ai.core.ThinkingLevel

public fun buildBaseOptions(
    model: Model<*>,
    options: SimpleStreamOptions?,
    apiKey: String? = null,
): StreamOptions =
    StreamOptions(
        temperature = options?.temperature,
        maxTokens = options?.maxTokens ?: minOf(model.maxTokens, 32_000),
        signal = options?.signal,
        apiKey = apiKey ?: options?.apiKey,
        transport = options?.transport,
        cacheRetention = options?.cacheRetention,
        sessionId = options?.sessionId,
        onPayload = options?.onPayload,
        onResponse = options?.onResponse,
        headers = options?.headers.orEmpty(),
        maxRetryDelayMs = options?.maxRetryDelayMs,
        metadata = options?.metadata.orEmpty(),
    )

public fun clampReasoning(level: ThinkingLevel?): ThinkingLevel? =
    when (level) {
        ThinkingLevel.XHIGH -> ThinkingLevel.HIGH
        else -> level
    }

public data class ThinkingAdjustment(
    val maxTokens: Int,
    val thinkingBudget: Int,
)

public fun adjustMaxTokensForThinking(
    baseMaxTokens: Int,
    modelMaxTokens: Int,
    reasoningLevel: ThinkingLevel,
    customBudgets: ThinkingBudgets?,
): ThinkingAdjustment {
    val defaultBudgets: ThinkingBudgets =
        ThinkingBudgets(
            minimal = 1024,
            low = 2048,
            medium = 8192,
            high = 16_384,
        )
    val budgets: ThinkingBudgets =
        ThinkingBudgets(
            minimal = customBudgets?.minimal ?: defaultBudgets.minimal,
            low = customBudgets?.low ?: defaultBudgets.low,
            medium = customBudgets?.medium ?: defaultBudgets.medium,
            high = customBudgets?.high ?: defaultBudgets.high,
        )
    val level: ThinkingLevel = clampReasoning(reasoningLevel) ?: ThinkingLevel.HIGH
    var thinkingBudget: Int =
        when (level) {
            ThinkingLevel.MINIMAL -> budgets.minimal
            ThinkingLevel.LOW -> budgets.low
            ThinkingLevel.MEDIUM -> budgets.medium
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> budgets.high
        } ?: 1024

    val maxTokens: Int = minOf(baseMaxTokens + thinkingBudget, modelMaxTokens)
    val minOutputTokens: Int = 1024
    if (maxTokens <= thinkingBudget) {
        thinkingBudget = maxOf(0, maxTokens - minOutputTokens)
    }

    return ThinkingAdjustment(maxTokens = maxTokens, thinkingBudget = thinkingBudget)
}
