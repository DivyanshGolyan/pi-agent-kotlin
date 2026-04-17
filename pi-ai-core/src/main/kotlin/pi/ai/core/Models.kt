package pi.ai.core

private val anthropicModels: Map<String, Model<String>> =
    linkedMapOf(
        "claude-3-5-haiku-20241022" to
            anthropicModel(
                id = "claude-3-5-haiku-20241022",
                name = "Claude Haiku 3.5",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.8, 4.0, 0.08, 1.0),
                contextWindow = 200_000,
                maxTokens = 8_192,
            ),
        "claude-3-5-haiku-latest" to
            anthropicModel(
                id = "claude-3-5-haiku-latest",
                name = "Claude Haiku 3.5 (latest)",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.8, 4.0, 0.08, 1.0),
                contextWindow = 200_000,
                maxTokens = 8_192,
            ),
        "claude-3-5-sonnet-20240620" to
            anthropicModel(
                id = "claude-3-5-sonnet-20240620",
                name = "Claude Sonnet 3.5",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 8_192,
            ),
        "claude-3-5-sonnet-20241022" to
            anthropicModel(
                id = "claude-3-5-sonnet-20241022",
                name = "Claude Sonnet 3.5 v2",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 8_192,
            ),
        "claude-3-7-sonnet-20250219" to
            anthropicModel(
                id = "claude-3-7-sonnet-20250219",
                name = "Claude Sonnet 3.7",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-3-haiku-20240307" to
            anthropicModel(
                id = "claude-3-haiku-20240307",
                name = "Claude Haiku 3",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.25, 1.25, 0.03, 0.3),
                contextWindow = 200_000,
                maxTokens = 4_096,
            ),
        "claude-3-opus-20240229" to
            anthropicModel(
                id = "claude-3-opus-20240229",
                name = "Claude Opus 3",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(15.0, 75.0, 1.5, 18.75),
                contextWindow = 200_000,
                maxTokens = 4_096,
            ),
        "claude-3-sonnet-20240229" to
            anthropicModel(
                id = "claude-3-sonnet-20240229",
                name = "Claude Sonnet 3",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 0.3),
                contextWindow = 200_000,
                maxTokens = 4_096,
            ),
        "claude-haiku-4-5" to
            anthropicModel(
                id = "claude-haiku-4-5",
                name = "Claude Haiku 4.5 (latest)",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(1.0, 5.0, 0.1, 1.25),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-haiku-4-5-20251001" to
            anthropicModel(
                id = "claude-haiku-4-5-20251001",
                name = "Claude Haiku 4.5",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(1.0, 5.0, 0.1, 1.25),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-opus-4-0" to
            anthropicModel(
                id = "claude-opus-4-0",
                name = "Claude Opus 4 (latest)",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(15.0, 75.0, 1.5, 18.75),
                contextWindow = 200_000,
                maxTokens = 32_000,
            ),
        "claude-opus-4-1" to
            anthropicModel(
                id = "claude-opus-4-1",
                name = "Claude Opus 4.1 (latest)",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(15.0, 75.0, 1.5, 18.75),
                contextWindow = 200_000,
                maxTokens = 32_000,
            ),
        "claude-opus-4-1-20250805" to
            anthropicModel(
                id = "claude-opus-4-1-20250805",
                name = "Claude Opus 4.1",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(15.0, 75.0, 1.5, 18.75),
                contextWindow = 200_000,
                maxTokens = 32_000,
            ),
        "claude-opus-4-20250514" to
            anthropicModel(
                id = "claude-opus-4-20250514",
                name = "Claude Opus 4",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(15.0, 75.0, 1.5, 18.75),
                contextWindow = 200_000,
                maxTokens = 32_000,
            ),
        "claude-opus-4-5" to
            anthropicModel(
                id = "claude-opus-4-5",
                name = "Claude Opus 4.5 (latest)",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(5.0, 25.0, 0.5, 6.25),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-opus-4-5-20251101" to
            anthropicModel(
                id = "claude-opus-4-5-20251101",
                name = "Claude Opus 4.5",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(5.0, 25.0, 0.5, 6.25),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-opus-4-6" to
            anthropicModel(
                id = "claude-opus-4-6",
                name = "Claude Opus 4.6",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(5.0, 25.0, 0.5, 6.25),
                contextWindow = 1_000_000,
                maxTokens = 128_000,
            ),
        "claude-opus-4-7" to
            anthropicModel(
                id = "claude-opus-4-7",
                name = "Claude Opus 4.7",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(5.0, 25.0, 0.5, 6.25),
                contextWindow = 1_000_000,
                maxTokens = 128_000,
            ),
        "claude-sonnet-4-0" to
            anthropicModel(
                id = "claude-sonnet-4-0",
                name = "Claude Sonnet 4 (latest)",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-sonnet-4-20250514" to
            anthropicModel(
                id = "claude-sonnet-4-20250514",
                name = "Claude Sonnet 4",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-sonnet-4-5" to
            anthropicModel(
                id = "claude-sonnet-4-5",
                name = "Claude Sonnet 4.5 (latest)",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-sonnet-4-5-20250929" to
            anthropicModel(
                id = "claude-sonnet-4-5-20250929",
                name = "Claude Sonnet 4.5",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        "claude-sonnet-4-6" to
            anthropicModel(
                id = "claude-sonnet-4-6",
                name = "Claude Sonnet 4.6",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(3.0, 15.0, 0.3, 3.75),
                contextWindow = 1_000_000,
                maxTokens = 64_000,
            ),
    )

private val modelRegistry: Map<String, Map<String, Model<String>>> =
    mapOf(
        ANTHROPIC_PROVIDER to anthropicModels,
    )

private fun anthropicModel(
    id: String,
    name: String,
    reasoning: Boolean,
    input: Set<InputModality>,
    cost: ModelCost,
    contextWindow: Int,
    maxTokens: Int,
): Model<String> =
    Model(
        id = id,
        name = name,
        api = ANTHROPIC_MESSAGES_API,
        provider = ANTHROPIC_PROVIDER,
        baseUrl = "https://api.anthropic.com",
        reasoning = reasoning,
        input = input,
        cost = cost,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
    )

public fun getModel(
    provider: String,
    modelId: String,
): Model<String>? = modelRegistry[provider]?.get(modelId)

public fun getProviders(): List<String> = modelRegistry.keys.toList()

public fun getModels(provider: String): List<Model<String>> = modelRegistry[provider]?.values?.toList().orEmpty()

public fun calculateCost(
    model: Model<*>,
    usage: Usage,
): UsageCost {
    usage.cost.input = (model.cost.input / 1_000_000.0) * usage.input
    usage.cost.output = (model.cost.output / 1_000_000.0) * usage.output
    usage.cost.cacheRead = (model.cost.cacheRead / 1_000_000.0) * usage.cacheRead
    usage.cost.cacheWrite = (model.cost.cacheWrite / 1_000_000.0) * usage.cacheWrite
    usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite
    return usage.cost
}

public fun supportsXhigh(model: Model<*>): Boolean =
    model.id.contains("gpt-5.2") ||
        model.id.contains("gpt-5.3") ||
        model.id.contains("gpt-5.4") ||
        model.id.contains("opus-4-6") ||
        model.id.contains("opus-4.6") ||
        model.id.contains("opus-4-7") ||
        model.id.contains("opus-4.7")

public fun modelsAreEqual(
    first: Model<*>?,
    second: Model<*>?,
): Boolean = first != null && second != null && first.id == second.id && first.provider == second.provider
