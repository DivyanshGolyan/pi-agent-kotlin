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
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private val refreshLock = ReentrantLock()
    private val storageLock = ReentrantLock()

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

    public fun hasAuth(provider: String): Boolean {
        val auth = authByProvider[provider] ?: return false
        return when (auth.type) {
            "oauth" ->
                getOAuthProvider(provider) != null &&
                    auth.access != null &&
                    auth.refresh != null &&
                    auth.expires != null &&
                    (provider != OPENAI_CODEX_PROVIDER || !auth.accountId.isNullOrBlank())
            "api_key" -> !(auth.apiKey ?: auth.key).isNullOrBlank()
            else -> !(auth.apiKey ?: auth.key).isNullOrBlank()
        }
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
        persistProviderChange(provider, ProviderAuth(type = "api_key", key = apiKey, apiKey = apiKey, headers = headers))
    }

    public fun setOAuthCredentials(
        provider: String,
        credentials: OpenAICodexOAuthCredentials,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (provider == OPENAI_CODEX_PROVIDER) {
            require(!credentials.accountId.isNullOrBlank()) { "OpenAI Codex OAuth credentials require accountId" }
        }
        persistProviderChange(
            provider,
            ProviderAuth(
                type = "oauth",
                headers = headers,
                access = credentials.access,
                refresh = credentials.refresh,
                expires = credentials.expires,
                accountId = credentials.accountId,
            ),
        )
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
        persistProviderChange(provider, null)
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
        return runCatching { refreshOAuthAccessTokenWithLock(provider) }
            .getOrElse {
                load()
                val updated = getOAuthCredentials(provider) ?: return null
                if (System.currentTimeMillis() < updated.expires) {
                    oauthProvider.getApiKey(updated)
                } else {
                    null
                }
            }
    }

    private fun refreshOAuthAccessTokenWithLock(provider: String): String? {
        val oauthProvider = getOAuthProvider(provider) ?: return null
        return refreshLock.withLock {
            withStorageLock {
                loadUnlocked()
                val current = getOAuthCredentials(provider) ?: return@withStorageLock null
                if (provider == OPENAI_CODEX_PROVIDER && current.accountId.isNullOrBlank()) {
                    return@withStorageLock null
                }
                if (System.currentTimeMillis() < current.expires) {
                    return@withStorageLock oauthProvider.getApiKey(current)
                }
                val refreshed = oauthProvider.refreshToken(current)
                authByProvider[provider] =
                    ProviderAuth(
                        type = "oauth",
                        headers = authByProvider[provider]?.headers.orEmpty(),
                        access = refreshed.access,
                        refresh = refreshed.refresh,
                        expires = refreshed.expires,
                        accountId = refreshed.accountId,
                    )
                persistUnlocked()
                oauthProvider.getApiKey(refreshed)
            }
        }
    }

    private fun <T> withStorageLock(block: () -> T): T {
        return storageLock.withLock {
            val file = path ?: return@withLock block()
            val lockFile = file.resolveSibling("${file.fileName}.lock")
            lockFile.parent?.let(Files::createDirectories)
            FileChannel
                .open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .use { channel ->
                    channel.lock().use {
                        block()
                    }
                }
        }
    }

    private fun load() {
        withStorageLock {
            loadUnlocked()
        }
    }

    private fun loadUnlocked() {
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

    private fun persistProviderChange(
        provider: String,
        auth: ProviderAuth?,
    ) {
        withStorageLock {
            loadUnlocked()
            if (auth == null) {
                authByProvider.remove(provider)
            } else {
                authByProvider[provider] = auth
            }
            persistUnlocked()
        }
    }

    private fun persistUnlocked() {
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
