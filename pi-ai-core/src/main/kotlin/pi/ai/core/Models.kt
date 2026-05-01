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

private val googleModels: Map<String, Model<String>> =
    linkedMapOf(
        "gemini-1.5-flash" to
            googleModel(
                id = "gemini-1.5-flash",
                name = "Gemini 1.5 Flash",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.075, 0.3, 0.01875, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 8_192,
            ),
        "gemini-1.5-flash-8b" to
            googleModel(
                id = "gemini-1.5-flash-8b",
                name = "Gemini 1.5 Flash-8B",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.0375, 0.15, 0.01, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 8_192,
            ),
        "gemini-1.5-pro" to
            googleModel(
                id = "gemini-1.5-pro",
                name = "Gemini 1.5 Pro",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(1.25, 5.0, 0.3125, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 8_192,
            ),
        "gemini-2.0-flash" to
            googleModel(
                id = "gemini-2.0-flash",
                name = "Gemini 2.0 Flash",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.1, 0.4, 0.025, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 8_192,
            ),
        "gemini-2.0-flash-lite" to
            googleModel(
                id = "gemini-2.0-flash-lite",
                name = "Gemini 2.0 Flash Lite",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.075, 0.3, 0.0, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 8_192,
            ),
        "gemini-2.5-flash" to
            googleModel(
                id = "gemini-2.5-flash",
                name = "Gemini 2.5 Flash",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.3, 2.5, 0.075, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-flash-lite" to
            googleModel(
                id = "gemini-2.5-flash-lite",
                name = "Gemini 2.5 Flash Lite",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.1, 0.4, 0.025, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-flash-lite-preview-06-17" to
            googleModel(
                id = "gemini-2.5-flash-lite-preview-06-17",
                name = "Gemini 2.5 Flash Lite Preview 06-17",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.1, 0.4, 0.025, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-flash-lite-preview-09-2025" to
            googleModel(
                id = "gemini-2.5-flash-lite-preview-09-2025",
                name = "Gemini 2.5 Flash Lite Preview 09-25",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.1, 0.4, 0.025, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-flash-preview-04-17" to
            googleModel(
                id = "gemini-2.5-flash-preview-04-17",
                name = "Gemini 2.5 Flash Preview 04-17",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.15, 0.6, 0.0375, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-flash-preview-05-20" to
            googleModel(
                id = "gemini-2.5-flash-preview-05-20",
                name = "Gemini 2.5 Flash Preview 05-20",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.15, 0.6, 0.0375, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-flash-preview-09-2025" to
            googleModel(
                id = "gemini-2.5-flash-preview-09-2025",
                name = "Gemini 2.5 Flash Preview 09-25",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.3, 2.5, 0.075, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-pro" to
            googleModel(
                id = "gemini-2.5-pro",
                name = "Gemini 2.5 Pro",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(1.25, 10.0, 0.31, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-pro-preview-05-06" to
            googleModel(
                id = "gemini-2.5-pro-preview-05-06",
                name = "Gemini 2.5 Pro Preview 05-06",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(1.25, 10.0, 0.31, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-2.5-pro-preview-06-05" to
            googleModel(
                id = "gemini-2.5-pro-preview-06-05",
                name = "Gemini 2.5 Pro Preview 06-05",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(1.25, 10.0, 0.31, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-3-flash-preview" to
            googleModel(
                id = "gemini-3-flash-preview",
                name = "Gemini 3 Flash Preview",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.5, 3.0, 0.05, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-3-pro-preview" to
            googleModel(
                id = "gemini-3-pro-preview",
                name = "Gemini 3 Pro Preview",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(2.0, 12.0, 0.2, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 64_000,
            ),
        "gemini-3.1-flash-lite-preview" to
            googleModel(
                id = "gemini-3.1-flash-lite-preview",
                name = "Gemini 3.1 Flash Lite Preview",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.25, 1.5, 0.025, 1.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-3.1-pro-preview" to
            googleModel(
                id = "gemini-3.1-pro-preview",
                name = "Gemini 3.1 Pro Preview",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(2.0, 12.0, 0.2, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-3.1-pro-preview-customtools" to
            googleModel(
                id = "gemini-3.1-pro-preview-customtools",
                name = "Gemini 3.1 Pro Preview Custom Tools",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(2.0, 12.0, 0.2, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-flash-latest" to
            googleModel(
                id = "gemini-flash-latest",
                name = "Gemini Flash Latest",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.3, 2.5, 0.075, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-flash-lite-latest" to
            googleModel(
                id = "gemini-flash-lite-latest",
                name = "Gemini Flash-Lite Latest",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.1, 0.4, 0.025, 0.0),
                contextWindow = 1_048_576,
                maxTokens = 65_536,
            ),
        "gemini-live-2.5-flash" to
            googleModel(
                id = "gemini-live-2.5-flash",
                name = "Gemini Live 2.5 Flash",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.5, 2.0, 0.0, 0.0),
                contextWindow = 128_000,
                maxTokens = 8_000,
            ),
        "gemini-live-2.5-flash-preview-native-audio" to
            googleModel(
                id = "gemini-live-2.5-flash-preview-native-audio",
                name = "Gemini Live 2.5 Flash Preview Native Audio",
                reasoning = true,
                input = setOf(InputModality.TEXT),
                cost = ModelCost(0.5, 2.0, 0.0, 0.0),
                contextWindow = 131_072,
                maxTokens = 65_536,
            ),
        "gemma-3-27b-it" to
            googleModel(
                id = "gemma-3-27b-it",
                name = "Gemma 3 27B",
                reasoning = false,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 131_072,
                maxTokens = 8_192,
            ),
        "gemma-4-26b-it" to
            googleModel(
                id = "gemma-4-26b-it",
                name = "Gemma 4 26B",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 256_000,
                maxTokens = 8_192,
            ),
        "gemma-4-31b-it" to
            googleModel(
                id = "gemma-4-31b-it",
                name = "Gemma 4 31B",
                reasoning = true,
                input = setOf(InputModality.TEXT, InputModality.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 256_000,
                maxTokens = 8_192,
            ),
    )

private val openAiCodexModels: Map<String, Model<String>> =
    linkedMapOf(
        "gpt-5.1" to openAiCodexModel("gpt-5.1", "GPT-5.1", ModelCost(1.25, 10.0, 0.125, 0.0)),
        "gpt-5.1-codex-max" to openAiCodexModel("gpt-5.1-codex-max", "GPT-5.1 Codex Max", ModelCost(1.25, 10.0, 0.125, 0.0)),
        "gpt-5.1-codex-mini" to openAiCodexModel("gpt-5.1-codex-mini", "GPT-5.1 Codex Mini", ModelCost(0.25, 2.0, 0.025, 0.0)),
        "gpt-5.2" to openAiCodexModel("gpt-5.2", "GPT-5.2", ModelCost(1.75, 14.0, 0.175, 0.0)),
        "gpt-5.2-codex" to openAiCodexModel("gpt-5.2-codex", "GPT-5.2 Codex", ModelCost(1.75, 14.0, 0.175, 0.0)),
        "gpt-5.3-codex" to openAiCodexModel("gpt-5.3-codex", "GPT-5.3 Codex", ModelCost(1.75, 14.0, 0.175, 0.0)),
        "gpt-5.3-codex-spark" to
            openAiCodexModel(
                id = "gpt-5.3-codex-spark",
                name = "GPT-5.3 Codex Spark",
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                input = setOf(InputModality.TEXT),
                contextWindow = 128_000,
            ),
        "gpt-5.4" to openAiCodexModel("gpt-5.4", "GPT-5.4", ModelCost(2.5, 15.0, 0.25, 0.0)),
        "gpt-5.4-mini" to openAiCodexModel("gpt-5.4-mini", "GPT-5.4 Mini", ModelCost(0.75, 4.5, 0.075, 0.0)),
        "gpt-5.5" to openAiCodexModel("gpt-5.5", "GPT-5.5", ModelCost(5.0, 30.0, 0.5, 0.0)),
    )

private val modelRegistry: Map<String, Map<String, Model<String>>> =
    mapOf(
        ANTHROPIC_PROVIDER to anthropicModels,
        GOOGLE_PROVIDER to googleModels,
        OPENAI_CODEX_PROVIDER to openAiCodexModels,
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

private fun googleModel(
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
        api = GOOGLE_GENERATIVE_AI_API,
        provider = GOOGLE_PROVIDER,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        reasoning = reasoning,
        input = input,
        cost = cost,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
    )

private fun openAiCodexModel(
    id: String,
    name: String,
    cost: ModelCost,
    input: Set<InputModality> = setOf(InputModality.TEXT, InputModality.IMAGE),
    contextWindow: Int = 272_000,
    maxTokens: Int = 128_000,
): Model<String> =
    Model(
        id = id,
        name = name,
        api = OPENAI_CODEX_RESPONSES_API,
        provider = OPENAI_CODEX_PROVIDER,
        baseUrl = "https://chatgpt.com/backend-api",
        reasoning = true,
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
        model.id.contains("gpt-5.5") ||
        model.id.contains("opus-4-6") ||
        model.id.contains("opus-4.6") ||
        model.id.contains("opus-4-7") ||
        model.id.contains("opus-4.7")

public fun modelsAreEqual(
    first: Model<*>?,
    second: Model<*>?,
): Boolean = first != null && second != null && first.id == second.id && first.provider == second.provider
