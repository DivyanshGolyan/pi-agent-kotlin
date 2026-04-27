package pi.coding.agent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pi.ai.core.OPENAI_CODEX_PROVIDER
import pi.ai.core.providers.OAuthLoginCallbacks
import pi.ai.core.providers.OpenAICodexOAuthCredentials
import pi.ai.core.providers.getOAuthProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

private data class ProviderAuth(
    val type: String? = null,
    val key: String? = null,
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val access: String? = null,
    val refresh: String? = null,
    val expires: Long? = null,
    val accountId: String? = null,
)

public class AuthStorage private constructor(
    private val path: Path?,
) {
    private val authByProvider: MutableMap<String, ProviderAuth> = linkedMapOf()

    init {
        load()
    }

    public fun getApiKey(provider: String): String? {
        val auth = authByProvider[provider] ?: return null
        if (auth.type == "oauth") {
            return getOAuthAccessToken(provider)
        }
        return auth.apiKey ?: auth.key
    }

    public fun getHeaders(provider: String): Map<String, String> {
        val auth = authByProvider[provider] ?: return emptyMap()
        val headers = auth.headers.toMutableMap()
        if (provider == OPENAI_CODEX_PROVIDER && auth.type == "oauth") {
            auth.accountId?.let { headers["chatgpt-account-id"] = it }
        }
        return headers
    }

    public fun getOAuthCredentials(provider: String): OpenAICodexOAuthCredentials? {
        val auth = authByProvider[provider] ?: return null
        if (auth.type != "oauth") {
            return null
        }
        return OpenAICodexOAuthCredentials(
            access = auth.access ?: return null,
            refresh = auth.refresh ?: return null,
            expires = auth.expires ?: return null,
            accountId = auth.accountId,
        )
    }

    public fun setApiKey(
        provider: String,
        apiKey: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        authByProvider[provider] = ProviderAuth(type = "api_key", key = apiKey, apiKey = apiKey, headers = headers)
        persist()
    }

    public fun setOAuthCredentials(
        provider: String,
        credentials: OpenAICodexOAuthCredentials,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (provider == OPENAI_CODEX_PROVIDER) {
            require(!credentials.accountId.isNullOrBlank()) { "OpenAI Codex OAuth credentials require accountId" }
        }
        authByProvider[provider] =
            ProviderAuth(
                type = "oauth",
                headers = headers,
                access = credentials.access,
                refresh = credentials.refresh,
                expires = credentials.expires,
                accountId = credentials.accountId,
            )
        persist()
    }

    public suspend fun login(
        provider: String,
        callbacks: OAuthLoginCallbacks,
    ) {
        val oauthProvider = getOAuthProvider(provider) ?: error("Unknown OAuth provider: $provider")
        val credentials = oauthProvider.login(callbacks)
        setOAuthCredentials(provider, credentials)
    }

    public fun clear(provider: String) {
        authByProvider.remove(provider)
        persist()
    }

    public fun logout(provider: String) {
        clear(provider)
    }

    private fun getOAuthAccessToken(provider: String): String? {
        val current = getOAuthCredentials(provider) ?: return null
        val oauthProvider = getOAuthProvider(provider) ?: return null
        if (provider == OPENAI_CODEX_PROVIDER && current.accountId.isNullOrBlank()) {
            return null
        }
        if (System.currentTimeMillis() < current.expires) {
            return oauthProvider.getApiKey(current)
        }
        val refreshed = runCatching { oauthProvider.refreshToken(current) }.getOrNull() ?: return null
        setOAuthCredentials(provider, refreshed, authByProvider[provider]?.headers.orEmpty())
        return oauthProvider.getApiKey(refreshed)
    }

    private fun load() {
        val file = path ?: return
        if (!file.exists()) {
            return
        }

        runCatching {
            val parsed = authJson.parseToJsonElement(Files.readString(file)).jsonObject
            val providers = parsed["providers"]?.jsonObject ?: parsed
            authByProvider.clear()
            providers.forEach { (provider, element) ->
                (element as? JsonObject)?.let { authByProvider[provider] = parseProviderAuth(it) }
            }
        }
    }

    private fun persist() {
        val file = path ?: return
        file.parent?.let(Files::createDirectories)
        val content = authJson.encodeToString(JsonObject.serializer(), toJsonObject())
        Files.writeString(
            file,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun toJsonObject(): JsonObject =
        buildJsonObject {
            authByProvider.forEach { (provider, auth) ->
                put(
                    provider,
                    buildJsonObject {
                        if (auth.type == "oauth") {
                            put("type", JsonPrimitive("oauth"))
                            auth.access?.let { put("access", JsonPrimitive(it)) }
                            auth.refresh?.let { put("refresh", JsonPrimitive(it)) }
                            auth.expires?.let { put("expires", JsonPrimitive(it)) }
                            auth.accountId?.let { put("accountId", JsonPrimitive(it)) }
                        } else {
                            put("type", JsonPrimitive("api_key"))
                            put("key", JsonPrimitive(auth.key ?: auth.apiKey.orEmpty()))
                        }
                        if (auth.headers.isNotEmpty()) {
                            put(
                                "headers",
                                buildJsonObject {
                                    auth.headers.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
                                },
                            )
                        }
                    },
                )
            }
        }

    public companion object {
        public fun create(path: String? = null): AuthStorage =
            AuthStorage(
                path =
                    path?.let(Paths::get)
                        ?: Paths.get(getAgentDir(), "auth.json"),
            )
    }
}

private val authJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        prettyPrint = true
    }

private fun parseProviderAuth(obj: JsonObject): ProviderAuth {
    val headers =
        obj["headers"]
            ?.jsonObject
            ?.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
            .orEmpty()
    return ProviderAuth(
        type = obj["type"]?.jsonPrimitive?.contentOrNull,
        key = obj["key"]?.jsonPrimitive?.contentOrNull,
        apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull,
        headers = headers,
        access = obj["access"]?.jsonPrimitive?.contentOrNull,
        refresh = obj["refresh"]?.jsonPrimitive?.contentOrNull,
        expires = obj["expires"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        accountId = obj["accountId"]?.jsonPrimitive?.contentOrNull,
    )
}
