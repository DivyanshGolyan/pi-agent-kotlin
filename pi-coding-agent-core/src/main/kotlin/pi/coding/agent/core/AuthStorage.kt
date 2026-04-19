package pi.coding.agent.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

@Serializable
private data class AuthFile(
    val providers: Map<String, ProviderAuth> = emptyMap(),
)

@Serializable
private data class ProviderAuth(
    val apiKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

public class AuthStorage private constructor(
    private val path: Path?,
) {
    private val authByProvider: MutableMap<String, ProviderAuth> = linkedMapOf()

    init {
        load()
    }

    public fun getApiKey(provider: String): String? = authByProvider[provider]?.apiKey

    public fun getHeaders(provider: String): Map<String, String> = authByProvider[provider]?.headers.orEmpty()

    public fun setApiKey(
        provider: String,
        apiKey: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        authByProvider[provider] = ProviderAuth(apiKey = apiKey, headers = headers)
        persist()
    }

    public fun clear(provider: String) {
        authByProvider.remove(provider)
        persist()
    }

    private fun load() {
        val file = path ?: return
        if (!file.exists()) {
            return
        }

        runCatching {
            val parsed = Json.decodeFromString<AuthFile>(Files.readString(file))
            authByProvider.clear()
            authByProvider.putAll(parsed.providers)
        }
    }

    private fun persist() {
        val file = path ?: return
        file.parent?.let(Files::createDirectories)
        val content = Json.encodeToString(AuthFile(providers = authByProvider.toMap()))
        Files.writeString(
            file,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
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
