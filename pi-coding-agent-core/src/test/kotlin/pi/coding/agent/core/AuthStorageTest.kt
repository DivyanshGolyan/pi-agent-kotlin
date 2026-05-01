package pi.coding.agent.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pi.ai.core.OPENAI_CODEX_PROVIDER
import pi.ai.core.getModel
import pi.ai.core.providers.OAuthLoginCallbacks
import pi.ai.core.providers.OpenAICodexOAuthCredentials
import java.nio.file.Files

class AuthStorageTest {
    @Test
    fun `persists API keys in upstream flat auth shape`() {
        val path = Files.createTempFile("auth", ".json")
        val auth = AuthStorage.create(path.toString())

        auth.setApiKey("anthropic", "sk-test")

        assertEquals("sk-test", auth.getApiKey("anthropic"))
        assertEquals(
            """
            {
                "anthropic": {
                    "type": "api_key",
                    "key": "sk-test"
                }
            }
            """.trimIndent(),
            Files.readString(path).trim(),
        )
    }

    @Test
    fun `persisted provider changes merge with current auth file`() {
        val path = Files.createTempFile("auth", ".json")
        val first = AuthStorage.create(path.toString())
        val second = AuthStorage.create(path.toString())

        first.setApiKey("anthropic", "sk-anthropic")
        second.setOAuthCredentials(
            OPENAI_CODEX_PROVIDER,
            OpenAICodexOAuthCredentials(
                access = "access",
                refresh = "refresh",
                expires = System.currentTimeMillis() + 60_000,
                accountId = "acct_123",
            ),
        )

        val reloaded = AuthStorage.create(path.toString())

        assertEquals("sk-anthropic", reloaded.getApiKey("anthropic"))
        assertEquals("access", reloaded.getApiKey(OPENAI_CODEX_PROVIDER))
    }

    @Test
    fun `reads legacy nested providers shape`() {
        val path = Files.createTempFile("auth", ".json")
        Files.writeString(path, """{"providers":{"anthropic":{"apiKey":"sk-old","headers":{"x-test":"1"}}}}""")

        val auth = AuthStorage.create(path.toString())

        assertEquals("sk-old", auth.getApiKey("anthropic"))
        assertEquals("1", auth.getHeaders("anthropic")["x-test"])
    }

    @Test
    fun `stores OAuth credentials and returns access token`() {
        val path = Files.createTempFile("auth", ".json")
        val auth = AuthStorage.create(path.toString())

        auth.setOAuthCredentials(
            OPENAI_CODEX_PROVIDER,
            OpenAICodexOAuthCredentials(
                access = "access",
                refresh = "refresh",
                expires = System.currentTimeMillis() + 60_000,
                accountId = "acct_123",
            ),
        )

        assertEquals("access", auth.getApiKey(OPENAI_CODEX_PROVIDER))
        assertEquals("acct_123", auth.getHeaders(OPENAI_CODEX_PROVIDER)["chatgpt-account-id"])
        assertNotNull(auth.getOAuthCredentials(OPENAI_CODEX_PROVIDER))
    }

    @Test
    fun `Codex OAuth storage rejects credentials without account id`() {
        val path = Files.createTempFile("auth", ".json")
        val auth = AuthStorage.create(path.toString())

        assertThrows(IllegalArgumentException::class.java) {
            auth.setOAuthCredentials(
                OPENAI_CODEX_PROVIDER,
                OpenAICodexOAuthCredentials(
                    access = "access",
                    refresh = "refresh",
                    expires = System.currentTimeMillis() + 60_000,
                    accountId = null,
                ),
            )
        }
    }

    @Test
    fun `stored Codex OAuth without account id is not returned as usable access`() {
        val path = Files.createTempFile("auth", ".json")
        Files.writeString(
            path,
            """
            {
                "$OPENAI_CODEX_PROVIDER": {
                    "type": "oauth",
                    "access": "access",
                    "refresh": "refresh",
                    "expires": ${System.currentTimeMillis() + 60_000}
                }
            }
            """.trimIndent(),
        )

        val auth = AuthStorage.create(path.toString())

        assertNull(auth.getApiKey(OPENAI_CODEX_PROVIDER))
    }

    @Test
    fun `expired OAuth access rereads fresh credentials under refresh lock`() {
        val path = Files.createTempFile("auth", ".json")
        Files.writeString(
            path,
            """
            {
                "$OPENAI_CODEX_PROVIDER": {
                    "type": "oauth",
                    "access": "stale-access",
                    "refresh": "stale-refresh",
                    "expires": ${System.currentTimeMillis() - 60_000},
                    "accountId": "acct_123"
                }
            }
            """.trimIndent(),
        )
        val auth = AuthStorage.create(path.toString())
        Files.writeString(
            path,
            """
            {
                "$OPENAI_CODEX_PROVIDER": {
                    "type": "oauth",
                    "access": "fresh-access",
                    "refresh": "fresh-refresh",
                    "expires": ${System.currentTimeMillis() + 60_000},
                    "accountId": "acct_123"
                }
            }
            """.trimIndent(),
        )

        assertEquals("fresh-access", auth.getApiKey(OPENAI_CODEX_PROVIDER))
    }

    @Test
    fun `model registry reports OAuth-backed Codex models`() {
        val path = Files.createTempFile("auth", ".json")
        val auth = AuthStorage.create(path.toString())
        auth.setOAuthCredentials(
            OPENAI_CODEX_PROVIDER,
            OpenAICodexOAuthCredentials(
                access = "access",
                refresh = "refresh",
                expires = System.currentTimeMillis() + 60_000,
                accountId = "acct_123",
            ),
        )
        val registry = ModelRegistry.create(auth)

        assertEquals(true, registry.isUsingOAuth(getModel(OPENAI_CODEX_PROVIDER, "gpt-5.4-mini")!!))
    }

    @Test
    fun `login rejects unknown OAuth provider through auth storage surface`() {
        val auth = AuthStorage.create(Files.createTempFile("auth", ".json").toString())
        val callbacks =
            OAuthLoginCallbacks(
                onAuth = {},
                onPrompt = { "" },
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { auth.login("unknown-provider", callbacks) }
            }

        assertEquals("Unknown OAuth provider: unknown-provider", error.message)
    }

    @Test
    fun `logout removes stored credentials`() {
        val path = Files.createTempFile("auth", ".json")
        val auth = AuthStorage.create(path.toString())
        auth.setApiKey("anthropic", "sk-test")

        auth.logout("anthropic")

        assertNull(auth.getApiKey("anthropic"))
    }
}
