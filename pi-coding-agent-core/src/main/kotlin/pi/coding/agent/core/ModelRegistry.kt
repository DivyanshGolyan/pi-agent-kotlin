package pi.coding.agent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.getModel
import pi.ai.core.getModels
import pi.ai.core.getProviders
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

public data class ApiKeyLookupResult(
    val ok: Boolean,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null,
)

public class ModelRegistry private constructor(
    private val authStorage: AuthStorage,
    private val modelsPath: Path?,
) {
    private val providerModels: MutableMap<String, MutableMap<String, Model<*>>> = linkedMapOf()
    private val providerRequestConfigs: MutableMap<String, ProviderRequestConfig> = linkedMapOf()
    private val modelRequestHeaders: MutableMap<String, Map<String, String>> = linkedMapOf()

    private var loadError: String? = null

    init {
        loadModels()
    }

    public fun refresh() {
        providerRequestConfigs.clear()
        modelRequestHeaders.clear()
        loadError = null
        loadModels()
    }

    public fun getError(): String? = loadError

    public fun getAll(): List<Model<*>> = providerModels.values.flatMap { it.values }

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
        val providerConfig = providerRequestConfigs[model.provider]
        val apiKey = getApiKeyForProvider(model.provider)
        val providerHeaders =
            resolveHeadersOrError(providerConfig?.headers, "provider \"${model.provider}\"")
        if (providerHeaders == null) {
            return ApiKeyLookupResult(
                ok = false,
                error = "Failed to resolve provider headers for ${model.provider}",
            )
        }
        val modelHeaders =
            resolveHeadersOrError(
                modelRequestHeaders[getModelRequestKey(model.provider, model.id)],
                "model \"${model.provider}/${model.id}\"",
            )
        if (modelHeaders == null) {
            return ApiKeyLookupResult(
                ok = false,
                error = "Failed to resolve model headers for ${model.provider}/${model.id}",
            )
        }

        if (providerConfig?.authHeader == true && apiKey == null) {
            return ApiKeyLookupResult(ok = false, error = "No API key found for \"${model.provider}\"")
        }

        val headers =
            buildMap {
                putAll(model.headers)
                putAll(authStorage.getHeaders(model.provider))
                putAll(providerHeaders)
                putAll(modelHeaders)
                if (providerConfig?.authHeader == true && apiKey != null) {
                    put("Authorization", "Bearer $apiKey")
                }
            }

        return if (apiKey != null || headers.isNotEmpty()) {
            ApiKeyLookupResult(ok = true, apiKey = apiKey, headers = headers)
        } else {
            ApiKeyLookupResult(
                ok = false,
                error = "No API key configured for ${model.provider}",
            )
        }
    }

    public fun isUsingOAuth(model: Model<*>): Boolean = authStorage.getOAuthCredentials(model.provider) != null

    public fun registerProvider(
        provider: String,
        models: List<Model<*>>,
    ) {
        providerModels.getOrPut(provider) { linkedMapOf() }.apply {
            models.forEach { put(it.id, it) }
        }
    }

    private fun loadModels() {
        val custom = loadCustomModels(modelsPath)
        if (custom.error != null) {
            loadError = custom.error
        }

        val builtInModels = loadBuiltInModels(custom.providerOverrides, custom.modelOverrides)
        val merged = mergeCustomModels(builtInModels, custom.models)
        providerModels.clear()
        merged.forEach { model ->
            providerModels.getOrPut(model.provider) { linkedMapOf() }[model.id] = model
        }
    }

    private fun loadBuiltInModels(
        providerOverrides: Map<String, ProviderOverride>,
        modelOverrides: Map<String, Map<String, ModelOverride>>,
    ): List<Model<*>> =
        getProviders().flatMap { provider ->
            val providerOverride = providerOverrides[provider]
            val perModelOverrides = modelOverrides[provider].orEmpty()
            getModels(provider).map { model ->
                val providerApplied =
                    if (providerOverride == null) {
                        model
                    } else {
                        model.copy(baseUrl = providerOverride.baseUrl ?: model.baseUrl)
                    }
                perModelOverrides[model.id]?.let { applyModelOverride(providerApplied, it) } ?: providerApplied
            }
        }

    private fun mergeCustomModels(
        builtInModels: List<Model<*>>,
        customModels: List<Model<*>>,
    ): List<Model<*>> {
        val merged = builtInModels.toMutableList()
        customModels.forEach { customModel ->
            val index = merged.indexOfFirst { it.provider == customModel.provider && it.id == customModel.id }
            if (index >= 0) {
                merged[index] = customModel
            } else {
                merged += customModel
            }
        }
        return merged
    }

    private fun loadCustomModels(path: Path?): CustomModelsResult {
        if (path == null || !path.exists()) {
            return CustomModelsResult()
        }
        val parsed =
            try {
                registryJson.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8))
            } catch (error: Throwable) {
                return CustomModelsResult(
                    error = "Failed to parse models.json: ${error.message}\n\nFile: $path",
                )
            }
        val root =
            parsed as? JsonObject
                ?: return CustomModelsResult(error = "Invalid models.json schema:\n  - root: must be an object\n\nFile: $path")
        val providers =
            root["providers"] as? JsonObject
                ?: return CustomModelsResult(error = "Invalid models.json schema:\n  - /providers: required\n\nFile: $path")

        val providerOverrides = linkedMapOf<String, ProviderOverride>()
        val modelOverrides = linkedMapOf<String, Map<String, ModelOverride>>()
        val customModels = mutableListOf<Model<*>>()
        val builtInProviders = getProviders().toSet()

        try {
            providers.forEach { (providerName, element) ->
                val provider = element as? JsonObject ?: error("""Provider $providerName: config must be an object.""")
                validateProviderConfig(providerName, provider, builtInProviders)
                val providerConfig = parseProviderConfig(provider)
                storeProviderRequestConfig(providerName, providerConfig)

                if (providerConfig.baseUrl != null) {
                    providerOverrides[providerName] = ProviderOverride(baseUrl = providerConfig.baseUrl)
                }
                if (providerConfig.modelOverrides.isNotEmpty()) {
                    modelOverrides[providerName] = providerConfig.modelOverrides
                    providerConfig.modelOverrides.forEach { (modelId, override) ->
                        storeModelHeaders(providerName, modelId, override.headers)
                    }
                }
                customModels += parseCustomModels(providerName, providerConfig, builtInProviders)
            }
        } catch (error: Throwable) {
            providerRequestConfigs.clear()
            modelRequestHeaders.clear()
            return CustomModelsResult(error = "Failed to load models.json: ${error.message}\n\nFile: $path")
        }

        return CustomModelsResult(
            models = customModels,
            providerOverrides = providerOverrides,
            modelOverrides = modelOverrides,
        )
    }

    private fun validateProviderConfig(
        providerName: String,
        provider: JsonObject,
        builtInProviders: Set<String>,
    ) {
        validateProviderShape(providerName, provider)
        val isBuiltIn = providerName in builtInProviders
        val models = provider["models"] as? JsonArray ?: JsonArray(emptyList())
        val hasModelOverrides = (provider["modelOverrides"] as? JsonObject)?.isNotEmpty() == true

        if (models.isEmpty()) {
            if (provider["baseUrl"] == null && provider["compat"] == null && !hasModelOverrides) {
                error("""Provider $providerName: must specify "baseUrl", "compat", "modelOverrides", or "models".""")
            }
        } else if (!isBuiltIn) {
            if (provider["baseUrl"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                error("""Provider $providerName: "baseUrl" is required when defining custom models.""")
            }
            if (provider["apiKey"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                error("""Provider $providerName: "apiKey" is required when defining custom models.""")
            }
        }

        models.forEach { item ->
            val model = item as? JsonObject ?: error("""Provider $providerName: model definitions must be objects.""")
            val id = model["id"]?.jsonPrimitive?.contentOrNull
            if (id.isNullOrBlank()) {
                error("Provider $providerName: model missing \"id\"")
            }
            if (!isBuiltIn && provider["api"] == null && model["api"] == null) {
                error("""Provider $providerName, model $id: no "api" specified. Set at provider or model level.""")
            }
            model["contextWindow"]?.jsonPrimitive?.intOrNull?.let {
                if (it <= 0) error("""Provider $providerName, model $id: invalid contextWindow""")
            }
            model["maxTokens"]?.jsonPrimitive?.intOrNull?.let {
                if (it <= 0) error("""Provider $providerName, model $id: invalid maxTokens""")
            }
        }
    }

    private fun parseProviderConfig(provider: JsonObject): ProviderConfig =
        ProviderConfig(
            baseUrl = provider["baseUrl"]?.jsonPrimitive?.contentOrNull,
            apiKey = provider["apiKey"]?.jsonPrimitive?.contentOrNull,
            api = provider["api"]?.jsonPrimitive?.contentOrNull,
            headers = parseStringMap(provider["headers"]),
            authHeader = provider["authHeader"]?.jsonPrimitive?.booleanOrNull,
            models =
                (provider["models"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map(::parseModelDefinition)
                    .orEmpty(),
            modelOverrides =
                (provider["modelOverrides"] as? JsonObject)
                    ?.mapValues { (_, value) -> parseModelOverride(value as? JsonObject ?: JsonObject(emptyMap())) }
                    .orEmpty(),
        )

    private fun parseModelDefinition(model: JsonObject): ModelDefinition =
        ModelDefinition(
            id = model["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = model["name"]?.jsonPrimitive?.contentOrNull,
            api = model["api"]?.jsonPrimitive?.contentOrNull,
            baseUrl = model["baseUrl"]?.jsonPrimitive?.contentOrNull,
            reasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull,
            input = parseInputModalities(model["input"]),
            cost = parseModelCost(model["cost"] as? JsonObject),
            contextWindow = model["contextWindow"]?.jsonPrimitive?.intOrNull,
            maxTokens = model["maxTokens"]?.jsonPrimitive?.intOrNull,
            headers = parseStringMap(model["headers"]),
        )

    private fun parseModelOverride(model: JsonObject): ModelOverride =
        ModelOverride(
            name = model["name"]?.jsonPrimitive?.contentOrNull,
            reasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull,
            input = parseInputModalities(model["input"]),
            cost = parseCostOverride(model["cost"] as? JsonObject),
            contextWindow = model["contextWindow"]?.jsonPrimitive?.intOrNull,
            maxTokens = model["maxTokens"]?.jsonPrimitive?.intOrNull,
            headers = parseStringMap(model["headers"]),
        )

    private fun parseCustomModels(
        providerName: String,
        config: ProviderConfig,
        builtInProviders: Set<String>,
    ): List<Model<*>> {
        val defaults =
            if (providerName in builtInProviders) {
                getModels(providerName).firstOrNull()?.let { BuiltInDefaults(api = it.api, baseUrl = it.baseUrl) }
            } else {
                null
            }
        return config.models.mapNotNull { model ->
            val api = model.api ?: config.api ?: defaults?.api ?: return@mapNotNull null
            val baseUrl = model.baseUrl ?: config.baseUrl ?: defaults?.baseUrl ?: return@mapNotNull null
            storeModelHeaders(providerName, model.id, model.headers)
            Model(
                id = model.id,
                name = model.name ?: model.id,
                api = api,
                provider = providerName,
                baseUrl = baseUrl,
                reasoning = model.reasoning ?: false,
                input = model.input ?: setOf(InputModality.TEXT),
                cost = model.cost ?: ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
                contextWindow = model.contextWindow ?: 128_000,
                maxTokens = model.maxTokens ?: 16_384,
            )
        }
    }

    private fun applyModelOverride(
        model: Model<*>,
        override: ModelOverride,
    ): Model<*> =
        model.copy(
            name = override.name ?: model.name,
            reasoning = override.reasoning ?: model.reasoning,
            input = override.input ?: model.input,
            cost =
                override.cost?.let { cost ->
                    ModelCost(
                        input = cost.input ?: model.cost.input,
                        output = cost.output ?: model.cost.output,
                        cacheRead = cost.cacheRead ?: model.cost.cacheRead,
                        cacheWrite = cost.cacheWrite ?: model.cost.cacheWrite,
                    )
                } ?: model.cost,
            contextWindow = override.contextWindow ?: model.contextWindow,
            maxTokens = override.maxTokens ?: model.maxTokens,
        )

    private fun getApiKeyForProvider(provider: String): String? =
        authStorage.getApiKey(provider)
            ?: providerRequestConfigs[provider]?.apiKey?.let(::resolveConfigValue)
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

    private fun getModelRequestKey(
        provider: String,
        modelId: String,
    ): String = "$provider:$modelId"

    private fun storeProviderRequestConfig(
        providerName: String,
        config: ProviderConfig,
    ) {
        if (config.apiKey == null && config.headers.isEmpty() && config.authHeader != true) {
            return
        }
        providerRequestConfigs[providerName] =
            ProviderRequestConfig(
                apiKey = config.apiKey,
                headers = config.headers,
                authHeader = config.authHeader,
            )
    }

    private fun storeModelHeaders(
        providerName: String,
        modelId: String,
        headers: Map<String, String>,
    ) {
        val key = getModelRequestKey(providerName, modelId)
        if (headers.isEmpty()) {
            modelRequestHeaders.remove(key)
        } else {
            modelRequestHeaders[key] = headers
        }
    }

    public companion object {
        public fun create(
            authStorage: AuthStorage = AuthStorage.create(),
            modelsPath: String? = Paths.get(getAgentDir(), "models.json").toString(),
        ): ModelRegistry = ModelRegistry(authStorage, modelsPath?.let(Paths::get))

        public fun inMemory(authStorage: AuthStorage = AuthStorage.create()): ModelRegistry = ModelRegistry(authStorage, null)
    }
}

private val registryJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

private data class CustomModelsResult(
    val models: List<Model<*>> = emptyList(),
    val providerOverrides: Map<String, ProviderOverride> = emptyMap(),
    val modelOverrides: Map<String, Map<String, ModelOverride>> = emptyMap(),
    val error: String? = null,
)

private data class ProviderOverride(
    val baseUrl: String? = null,
)

private data class ProviderRequestConfig(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val authHeader: Boolean? = null,
)

private data class ProviderConfig(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val api: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val authHeader: Boolean? = null,
    val models: List<ModelDefinition> = emptyList(),
    val modelOverrides: Map<String, ModelOverride> = emptyMap(),
)

private data class ModelDefinition(
    val id: String,
    val name: String? = null,
    val api: String? = null,
    val baseUrl: String? = null,
    val reasoning: Boolean? = null,
    val input: Set<InputModality>? = null,
    val cost: ModelCost? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val headers: Map<String, String> = emptyMap(),
)

private data class ModelOverride(
    val name: String? = null,
    val reasoning: Boolean? = null,
    val input: Set<InputModality>? = null,
    val cost: CostOverride? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val headers: Map<String, String> = emptyMap(),
)

private data class BuiltInDefaults(
    val api: String,
    val baseUrl: String,
)

private data class CostOverride(
    val input: Double? = null,
    val output: Double? = null,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
)

private fun validateProviderShape(
    providerName: String,
    provider: JsonObject,
) {
    requireOptionalString(provider, "baseUrl", "Provider $providerName", minLength = true)
    requireOptionalString(provider, "apiKey", "Provider $providerName", minLength = true)
    requireOptionalString(provider, "api", "Provider $providerName", minLength = true)
    requireOptionalBoolean(provider, "authHeader", "Provider $providerName")
    requireOptionalObject(provider, "compat", "Provider $providerName")
    requireHeadersShape(provider["headers"], "Provider $providerName headers")

    provider["models"]?.let { models ->
        if (models !is JsonArray) {
            error("Provider $providerName: \"models\" must be an array.")
        }
        models.forEachIndexed { index, item ->
            val model = item as? JsonObject ?: error("Provider $providerName: model definitions must be objects.")
            validateModelDefinitionShape(providerName, index, model)
        }
    }

    provider["modelOverrides"]?.let { overrides ->
        if (overrides !is JsonObject) {
            error("Provider $providerName: \"modelOverrides\" must be an object.")
        }
        overrides.forEach { (modelId, item) ->
            val override = item as? JsonObject ?: error("Provider $providerName, model override $modelId: must be an object.")
            validateModelOverrideShape(providerName, modelId, override)
        }
    }
}

private fun validateModelDefinitionShape(
    providerName: String,
    index: Int,
    model: JsonObject,
) {
    val location = "Provider $providerName, model[$index]"
    requireOptionalString(model, "id", location, minLength = true)
    requireOptionalString(model, "name", location, minLength = true)
    requireOptionalString(model, "api", location, minLength = true)
    requireOptionalString(model, "baseUrl", location, minLength = true)
    requireOptionalBoolean(model, "reasoning", location)
    requireInputShape(model["input"], "$location input")
    requireModelCostShape(model["cost"], "$location cost", requireAllFields = true)
    requireOptionalPositiveInt(model, "contextWindow", location)
    requireOptionalPositiveInt(model, "maxTokens", location)
    requireHeadersShape(model["headers"], "$location headers")
    requireOptionalObject(model, "compat", location)
}

private fun validateModelOverrideShape(
    providerName: String,
    modelId: String,
    override: JsonObject,
) {
    val location = "Provider $providerName, model override $modelId"
    requireOptionalString(override, "name", location, minLength = true)
    requireOptionalBoolean(override, "reasoning", location)
    requireInputShape(override["input"], "$location input")
    requireModelCostShape(override["cost"], "$location cost", requireAllFields = false)
    requireOptionalPositiveInt(override, "contextWindow", location)
    requireOptionalPositiveInt(override, "maxTokens", location)
    requireHeadersShape(override["headers"], "$location headers")
    requireOptionalObject(override, "compat", location)
}

private fun requireOptionalString(
    obj: JsonObject,
    key: String,
    location: String,
    minLength: Boolean = false,
) {
    val element = obj[key] ?: return
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isJsonString()) {
        error("$location: \"$key\" must be a string.")
    }
    if (minLength && primitive.content.isBlank()) {
        error("$location: \"$key\" must not be empty.")
    }
}

private fun requireOptionalBoolean(
    obj: JsonObject,
    key: String,
    location: String,
) {
    val element = obj[key] ?: return
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.booleanOrNull == null) {
        error("$location: \"$key\" must be a boolean.")
    }
}

private fun requireOptionalPositiveInt(
    obj: JsonObject,
    key: String,
    location: String,
) {
    val element = obj[key] ?: return
    val primitive = element as? JsonPrimitive
    val value = primitive?.intOrNull
    if (value == null || value <= 0) {
        error("$location: \"$key\" must be a positive number.")
    }
}

private fun requireOptionalObject(
    obj: JsonObject,
    key: String,
    location: String,
) {
    val element = obj[key] ?: return
    if (element !is JsonObject) {
        error("$location: \"$key\" must be an object.")
    }
}

private fun requireHeadersShape(
    element: JsonElement?,
    location: String,
) {
    if (element == null) {
        return
    }
    val headers = element as? JsonObject ?: error("$location must be an object.")
    headers.forEach { (name, value) ->
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isJsonString()) {
            error("$location: \"$name\" must be a string.")
        }
    }
}

private fun requireInputShape(
    element: JsonElement?,
    location: String,
) {
    if (element == null) {
        return
    }
    val input = element as? JsonArray ?: error("$location must be an array.")
    input.forEach { item ->
        val value = (item as? JsonPrimitive)?.takeIf { it.isJsonString() }?.content
        if (value != "text" && value != "image") {
            error("$location must contain only \"text\" or \"image\".")
        }
    }
}

private fun requireModelCostShape(
    element: JsonElement?,
    location: String,
    requireAllFields: Boolean,
) {
    if (element == null) {
        return
    }
    val cost = element as? JsonObject ?: error("$location must be an object.")
    val fields = listOf("input", "output", "cacheRead", "cacheWrite")
    fields.forEach { field ->
        val value = (cost[field] as? JsonPrimitive)?.doubleOrNull
        if (value == null && (requireAllFields || cost[field] != null)) {
            error("$location: \"$field\" must be a number.")
        }
    }
}

private fun parseStringMap(element: JsonElement?): Map<String, String> =
    (element as? JsonObject)
        ?.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
        .orEmpty()

private fun JsonPrimitive.isJsonString(): Boolean = toString().startsWith("\"")

private fun parseInputModalities(element: JsonElement?): Set<InputModality>? =
    (element as? JsonArray)
        ?.mapNotNull { item ->
            when (item.jsonPrimitive.contentOrNull) {
                "text" -> InputModality.TEXT
                "image" -> InputModality.IMAGE
                else -> null
            }
        }?.toSet()

private fun parseModelCost(obj: JsonObject?): ModelCost? {
    if (obj == null) {
        return null
    }
    return ModelCost(
        input = obj["input"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        output = obj["output"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        cacheRead = obj["cacheRead"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        cacheWrite = obj["cacheWrite"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    )
}

private fun parseCostOverride(obj: JsonObject?): CostOverride? {
    if (obj == null) {
        return null
    }
    return CostOverride(
        input = obj["input"]?.jsonPrimitive?.doubleOrNull,
        output = obj["output"]?.jsonPrimitive?.doubleOrNull,
        cacheRead = obj["cacheRead"]?.jsonPrimitive?.doubleOrNull,
        cacheWrite = obj["cacheWrite"]?.jsonPrimitive?.doubleOrNull,
    )
}

private fun resolveHeadersOrError(
    headers: Map<String, String>?,
    description: String,
): Map<String, String>? =
    try {
        val resolved =
            headers?.mapValues { (name, value) ->
                resolveConfigValueOrThrow(value, "$description header \"$name\"")
            }
        resolved.orEmpty()
    } catch (_: Throwable) {
        null
    }

private fun resolveConfigValue(config: String): String? {
    if (config.startsWith("!")) {
        return executeCommand(config.removePrefix("!"))
    }
    return System.getenv(config) ?: config
}

private fun resolveConfigValueOrThrow(
    config: String,
    description: String,
): String =
    resolveConfigValue(config)
        ?: if (config.startsWith("!")) {
            error("Failed to resolve $description from shell command: ${config.removePrefix("!")}")
        } else {
            error("Failed to resolve $description")
        }

private fun executeCommand(command: String): String? =
    runCatching {
        val process =
            ProcessBuilder("sh", "-c", command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) {
            return@runCatching null
        }
        val reader = process.inputStream.bufferedReader()
        val output = reader.readText().trim()
        output.takeIf { it.isNotEmpty() }
    }.getOrNull()
