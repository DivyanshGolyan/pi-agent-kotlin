package pi.coding.agent.core

import pi.ai.core.Model
import pi.ai.core.getModel
import pi.ai.core.getModels
import pi.ai.core.getProviders

public data class ApiKeyLookupResult(
    val ok: Boolean,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null,
)

public class ModelRegistry private constructor(
    private val authStorage: AuthStorage,
) {
    private val providerModels: MutableMap<String, MutableMap<String, Model<*>>> =
        getProviders()
            .associateWithTo(linkedMapOf()) { provider ->
                getModels(provider).associateByTo(linkedMapOf()) { it.id }.toMutableMap()
            }

    public fun find(
        provider: String,
        modelId: String,
    ): Model<*>? = providerModels[provider]?.get(modelId) ?: getModel(provider, modelId)

    public fun getAvailable(): List<Model<*>> =
        providerModels
            .filterKeys(::hasConfiguredAuthForProvider)
            .values
            .flatMap { it.values }

    public fun hasConfiguredAuth(model: Model<*>): Boolean = hasConfiguredAuthForProvider(model.provider)

    public fun hasConfiguredAuthForProvider(provider: String): Boolean = getApiKeyForProvider(provider) != null

    public fun getApiKey(provider: String): String? = getApiKeyForProvider(provider)

    public fun getApiKeyAndHeaders(model: Model<*>): ApiKeyLookupResult {
        val apiKey = getApiKeyForProvider(model.provider)
        return if (apiKey != null) {
            ApiKeyLookupResult(
                ok = true,
                apiKey = apiKey,
                headers = authStorage.getHeaders(model.provider),
            )
        } else {
            ApiKeyLookupResult(
                ok = false,
                error = "No API key configured for ${model.provider}",
            )
        }
    }

    @Suppress("UnusedParameter")
    public fun isUsingOAuth(model: Model<*>): Boolean = false

    public fun registerProvider(
        provider: String,
        models: List<Model<*>>,
    ) {
        providerModels.getOrPut(provider) { linkedMapOf() }.apply {
            models.forEach { put(it.id, it) }
        }
    }

    private fun getApiKeyForProvider(provider: String): String? =
        authStorage.getApiKey(provider)
            ?: providerEnvVars(provider).firstNotNullOfOrNull(System::getenv)

    private fun providerEnvVars(provider: String): List<String> {
        val normalized = provider.replace(Regex("[^A-Za-z0-9]"), "_").uppercase()
        val candidates = linkedSetOf("${normalized}_API_KEY")
        if (provider.equals("anthropic", ignoreCase = true)) {
            candidates += "ANTHROPIC_API_KEY"
        }
        if (provider.equals("openai", ignoreCase = true)) {
            candidates += "OPENAI_API_KEY"
        }
        return candidates.toList()
    }

    public companion object {
        public fun create(
            authStorage: AuthStorage = AuthStorage.create(),
            @Suppress("UnusedParameter")
            modelsPath: String? = null,
        ): ModelRegistry = ModelRegistry(authStorage)
    }
}
