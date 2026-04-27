package pi.ai.core.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import pi.ai.core.OPENAI_CODEX_PROVIDER
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

public const val OPENAI_CODEX_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
public const val OPENAI_CODEX_AUTHORIZE_URL: String = "https://auth.openai.com/oauth/authorize"
public const val OPENAI_CODEX_TOKEN_URL: String = "https://auth.openai.com/oauth/token"
public const val OPENAI_CODEX_REDIRECT_URI: String = "http://localhost:1455/auth/callback"
public const val OPENAI_CODEX_SCOPE: String = "openid profile email offline_access"
public const val OPENAI_CODEX_JWT_CLAIM_PATH: String = "https://api.openai.com/auth"
public const val OPENAI_CODEX_CALLBACK_TIMEOUT_MILLIS: Long = 300_000L

public data class OpenAICodexPkce(
    val verifier: String,
    val challenge: String,
)

public data class OpenAICodexAuthorizationFlow(
    val verifier: String,
    val state: String,
    val url: String,
)

public data class OpenAICodexOAuthCredentials(
    val access: String,
    val refresh: String,
    val expires: Long,
    val accountId: String? = null,
)

public data class OpenAICodexCallbackResult(
    val code: String,
    val state: String,
)

public typealias OAuthCredentials = OpenAICodexOAuthCredentials

public data class OAuthAuthInfo(
    val url: String,
    val instructions: String? = null,
)

public data class OAuthPrompt(
    val message: String,
)

public data class OAuthLoginCallbacks(
    val onAuth: (OAuthAuthInfo) -> Unit,
    val onPrompt: suspend (OAuthPrompt) -> String,
    val onProgress: ((String) -> Unit)? = null,
    val onManualCodeInput: (suspend () -> String)? = null,
    val originator: String = "pi",
    val callbackTimeoutMillis: Long = OPENAI_CODEX_CALLBACK_TIMEOUT_MILLIS,
)

public interface OAuthProviderInterface {
    public val id: String
    public val name: String
    public val usesCallbackServer: Boolean

    public suspend fun login(callbacks: OAuthLoginCallbacks): OAuthCredentials

    public fun refreshToken(credentials: OAuthCredentials): OAuthCredentials

    public fun getApiKey(credentials: OAuthCredentials): String
}

public data class OpenAICodexAuthorizationInput(
    val code: String? = null,
    val state: String? = null,
)

private val oauthJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

private val oauthClient: OkHttpClient = OkHttpClient.Builder().build()

public fun generateOpenAICodexPkce(random: SecureRandom = SecureRandom()): OpenAICodexPkce {
    val verifierBytes = ByteArray(32)
    random.nextBytes(verifierBytes)
    val verifier = base64Url(verifierBytes)
    val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8))
    return OpenAICodexPkce(verifier = verifier, challenge = base64Url(challengeBytes))
}

public fun createOpenAICodexState(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

public fun createOpenAICodexAuthorizationFlow(originator: String = "pi"): OpenAICodexAuthorizationFlow {
    val pkce = generateOpenAICodexPkce()
    val state = createOpenAICodexState()
    val params =
        linkedMapOf(
            "response_type" to "code",
            "client_id" to OPENAI_CODEX_CLIENT_ID,
            "redirect_uri" to OPENAI_CODEX_REDIRECT_URI,
            "scope" to OPENAI_CODEX_SCOPE,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to originator,
        )
    return OpenAICodexAuthorizationFlow(
        verifier = pkce.verifier,
        state = state,
        url = "$OPENAI_CODEX_AUTHORIZE_URL?${params.toQueryString()}",
    )
}

public fun extractOpenAICodexAccountId(accessToken: String): String? {
    val parts = accessToken.split(".")
    if (parts.size != 3) {
        return null
    }
    return runCatching {
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
        val parsed = oauthJson.parseToJsonElement(payload).jsonObject
        parsed[OPENAI_CODEX_JWT_CLAIM_PATH]
            ?.jsonObject
            ?.get("chatgpt_account_id")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
}

public fun parseOpenAICodexAuthorizationInput(input: String): OpenAICodexAuthorizationInput {
    val value = input.trim()
    if (value.isEmpty()) {
        return OpenAICodexAuthorizationInput()
    }

    runCatching {
        val uri = URI(value)
        if (!uri.scheme.isNullOrBlank()) {
            val params = parseQuery(uri.rawQuery.orEmpty())
            return OpenAICodexAuthorizationInput(code = params["code"], state = params["state"])
        }
    }

    if (value.contains("#")) {
        val parts = value.split("#", limit = 2)
        return OpenAICodexAuthorizationInput(
            code = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
            state = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
        )
    }

    if (value.contains("code=")) {
        val params = parseQuery(value.removePrefix("?"))
        return OpenAICodexAuthorizationInput(code = params["code"], state = params["state"])
    }

    return OpenAICodexAuthorizationInput(code = value)
}

public fun exchangeOpenAICodexAuthorizationCode(
    code: String,
    verifier: String,
    redirectUri: String = OPENAI_CODEX_REDIRECT_URI,
): OpenAICodexOAuthCredentials? {
    val body =
        FormBody
            .Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", OPENAI_CODEX_CLIENT_ID)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .build()
    return requestOpenAICodexToken(body)
}

public fun refreshOpenAICodexAccessToken(refreshToken: String): OpenAICodexOAuthCredentials? {
    val body =
        FormBody
            .Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", OPENAI_CODEX_CLIENT_ID)
            .build()
    return requestOpenAICodexToken(body)
}

public suspend fun loginOpenAICodex(callbacks: OAuthLoginCallbacks): OAuthCredentials =
    loginOpenAICodex(callbacks) { code, verifier ->
        exchangeOpenAICodexAuthorizationCode(code, verifier)
    }

public class OpenAICodexLoopbackServer private constructor(
    private val serverSocket: ServerSocket,
    private val expectedState: String,
) {
    private val latch = CountDownLatch(1)

    @Volatile private var result: OpenAICodexCallbackResult? = null

    init {
        thread(name = "openai-codex-oauth-loopback", isDaemon = true) {
            runCatching {
                while (!serverSocket.isClosed) {
                    handle(serverSocket.accept())
                    if (result != null) {
                        close()
                        break
                    }
                }
            }
        }
    }

    public fun waitForCallback(timeoutMillis: Long): OpenAICodexCallbackResult? {
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        close()
        return result
    }

    public fun cancelWait() {
        latch.countDown()
    }

    public fun close() {
        runCatching { serverSocket.close() }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val requestLine =
                it
                    .getInputStream()
                    .bufferedReader()
                    .readLine()
                    .orEmpty()
            val path = requestLine.split(" ").getOrNull(1).orEmpty()
            val parsed = parseCallbackPath(path)
            when {
                parsed == null -> writeResponse(it, 404, "Callback route not found.")
                parsed.state != expectedState -> writeResponse(it, 400, "State mismatch.")
                parsed.code.isBlank() -> writeResponse(it, 400, "Missing authorization code.")
                else -> {
                    result = parsed
                    writeResponse(it, 200, "OpenAI authentication completed. You can close this window.")
                    latch.countDown()
                }
            }
        }
    }

    public companion object {
        public fun start(expectedState: String): OpenAICodexLoopbackServer {
            val socket = ServerSocket(1455, 1, InetAddress.getByName("127.0.0.1"))
            return OpenAICodexLoopbackServer(socket, expectedState)
        }

        public fun tryStart(expectedState: String): OpenAICodexLoopbackServer? = runCatching { start(expectedState) }.getOrNull()
    }
}

public val openAICodexOAuthProvider: OAuthProviderInterface =
    object : OAuthProviderInterface {
        override val id: String = OPENAI_CODEX_PROVIDER
        override val name: String = "ChatGPT Plus/Pro (Codex Subscription)"
        override val usesCallbackServer: Boolean = true

        override suspend fun login(callbacks: OAuthLoginCallbacks): OAuthCredentials = loginOpenAICodex(callbacks)

        override fun refreshToken(credentials: OAuthCredentials): OAuthCredentials =
            requireOpenAICodexAccountId(
                refreshOpenAICodexAccessToken(credentials.refresh)
                    ?: error("Failed to refresh OpenAI Codex token"),
            )

        override fun getApiKey(credentials: OAuthCredentials): String = credentials.access
    }

public fun getOAuthProvider(id: String): OAuthProviderInterface? =
    when (id) {
        OPENAI_CODEX_PROVIDER -> openAICodexOAuthProvider
        else -> null
    }

private fun requestOpenAICodexToken(body: FormBody): OpenAICodexOAuthCredentials? {
    val request =
        Request
            .Builder()
            .url(OPENAI_CODEX_TOKEN_URL)
            .post(body)
            .header("content-type", "application/x-www-form-urlencoded")
            .build()
    val response = oauthClient.newCall(request).execute()
    response.use {
        if (!it.isSuccessful) {
            return null
        }
        val parsed = oauthJson.decodeFromString<OpenAICodexTokenResponse>(it.body.string())
        val access = parsed.accessToken ?: return null
        val refresh = parsed.refreshToken ?: return null
        val expiresIn = parsed.expiresIn ?: return null
        val accountId = extractOpenAICodexAccountId(access) ?: return null
        return OpenAICodexOAuthCredentials(
            access = access,
            refresh = refresh,
            expires = System.currentTimeMillis() + expiresIn * 1000L,
            accountId = accountId,
        )
    }
}

internal suspend fun loginOpenAICodex(
    callbacks: OAuthLoginCallbacks,
    exchangeAuthorizationCode: (code: String, verifier: String) -> OAuthCredentials?,
): OAuthCredentials =
    coroutineScope {
        val flow = createOpenAICodexAuthorizationFlow(callbacks.originator)
        val server = OpenAICodexLoopbackServer.tryStart(flow.state)
        callbacks.onAuth(
            OAuthAuthInfo(
                url = flow.url,
                instructions = "A browser window should open. Complete login to finish.",
            ),
        )

        var code: String? = null
        try {
            val manualInput = callbacks.onManualCodeInput
            if (manualInput != null) {
                val callbackDeferred =
                    async(Dispatchers.IO) {
                        server?.waitForCallback(callbacks.callbackTimeoutMillis)
                    }
                val manualDeferred =
                    async {
                        runCatching { manualInput() }
                            .onSuccess { server?.cancelWait() }
                            .onFailure { server?.cancelWait() }
                            .getOrThrow()
                    }

                val callbackCode = callbackDeferred.await()?.code
                if (!callbackCode.isNullOrBlank()) {
                    code = callbackCode
                    manualDeferred.cancel()
                } else {
                    val parsed = parseAndValidateAuthorizationInput(manualDeferred.await(), flow.state)
                    code = parsed.code
                }
            } else {
                val callbackResult =
                    async(Dispatchers.IO) {
                        server?.waitForCallback(callbacks.callbackTimeoutMillis)
                    }.await()
                val callbackCode = callbackResult?.code
                if (!callbackCode.isNullOrBlank()) {
                    code = callbackCode
                }
            }

            if (code.isNullOrBlank()) {
                val input =
                    callbacks.onPrompt(
                        OAuthPrompt("Paste the authorization code (or full redirect URL):"),
                    )
                code = parseAndValidateAuthorizationInput(input, flow.state).code
            }

            val authorizationCode = code
            if (authorizationCode.isNullOrBlank()) {
                error("Missing authorization code")
            }

            requireOpenAICodexAccountId(
                exchangeAuthorizationCode(authorizationCode, flow.verifier)
                    ?: error("Token exchange failed"),
            )
        } finally {
            server?.close()
        }
    }

private fun parseCallbackPath(path: String): OpenAICodexCallbackResult? {
    val question = path.indexOf('?')
    val route = if (question >= 0) path.substring(0, question) else path
    if (route != "/auth/callback") {
        return null
    }
    val params = parseQuery(if (question >= 0) path.substring(question + 1) else "")
    return OpenAICodexCallbackResult(
        code = params["code"].orEmpty(),
        state = params["state"].orEmpty(),
    )
}

private fun parseQuery(query: String): Map<String, String> =
    query
        .split("&")
        .filter { it.isNotEmpty() }
        .associate {
            val parts = it.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
            key to value
        }

private fun parseAndValidateAuthorizationInput(
    input: String,
    expectedState: String,
): OpenAICodexAuthorizationInput {
    val parsed = parseOpenAICodexAuthorizationInput(input)
    if (parsed.state != null && parsed.state != expectedState) {
        error("State mismatch")
    }
    return parsed
}

private fun requireOpenAICodexAccountId(credentials: OAuthCredentials): OAuthCredentials {
    if (credentials.accountId.isNullOrBlank()) {
        error("Failed to extract accountId from token")
    }
    return credentials
}

private fun writeResponse(
    socket: Socket,
    status: Int,
    message: String,
) {
    val statusText = if (status == 200) "OK" else "Error"
    val body = "<html><body>$message</body></html>"
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    socket.getOutputStream().write(
        (
            "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        ).toByteArray(StandardCharsets.UTF_8),
    )
    socket.getOutputStream().write(bytes)
}

private fun Map<String, String>.toQueryString(): String =
    entries.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }

private fun String.urlEncode(): String =
    java.net.URLEncoder
        .encode(this, StandardCharsets.UTF_8.name())
        .replace("+", "%20")

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

@Serializable
private data class OpenAICodexTokenResponse(
    @kotlinx.serialization.SerialName("access_token")
    val accessToken: String? = null,
    @kotlinx.serialization.SerialName("refresh_token")
    val refreshToken: String? = null,
    @kotlinx.serialization.SerialName("expires_in")
    val expiresIn: Long? = null,
)
