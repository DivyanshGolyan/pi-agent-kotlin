package pi.coding.agent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.ANTHROPIC_PROVIDER
import pi.ai.core.InputModality
import java.nio.file.Files

class ModelRegistryTest {
    @Test
    fun `loads custom models and resolves provider auth headers`() {
        val auth = AuthStorage.create(Files.createTempFile("auth", ".json").toString())
        val modelsPath = Files.createTempFile("models", ".json")
        Files.writeString(
            modelsPath,
            """
            {
                "providers": {
                    "custom-ai": {
                        "baseUrl": "https://custom.example/v1",
                        "apiKey": "sk-custom",
                        "api": "anthropic-messages",
                        "authHeader": true,
                        "headers": {
                            "x-provider": "provider-header"
                        },
                        "models": [
                            {
                                "id": "custom-1",
                                "name": "Custom One",
                                "reasoning": true,
                                "input": ["text", "image"],
                                "cost": {
                                    "input": 1,
                                    "output": 2,
                                    "cacheRead": 0.5,
                                    "cacheWrite": 3
                                },
                                "contextWindow": 32000,
                                "maxTokens": 4096,
                                "headers": {
                                    "x-model": "model-header"
                                }
                            }
                        ]
                    }
                }
            }
            """.trimIndent(),
        )

        val registry = ModelRegistry.create(auth, modelsPath.toString())
        val model = registry.find("custom-ai", "custom-1")

        assertNotNull(model)
        model!!
        assertEquals("Custom One", model.name)
        assertEquals("https://custom.example/v1", model.baseUrl)
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), model.input)
        assertTrue(registry.getAvailable().any { it.provider == "custom-ai" && it.id == "custom-1" })

        val authResult = registry.getApiKeyAndHeaders(model)
        assertTrue(authResult.ok, authResult.error)
        assertEquals("sk-custom", authResult.apiKey)
        assertEquals("Bearer sk-custom", authResult.headers["Authorization"])
        assertEquals("provider-header", authResult.headers["x-provider"])
        assertEquals("model-header", authResult.headers["x-model"])
    }

    @Test
    fun `applies built in provider and model overrides from models json`() {
        val auth = AuthStorage.create(Files.createTempFile("auth", ".json").toString())
        auth.setApiKey(ANTHROPIC_PROVIDER, "sk-auth")
        val modelsPath = Files.createTempFile("models", ".json")
        Files.writeString(
            modelsPath,
            """
            {
                "providers": {
                    "$ANTHROPIC_PROVIDER": {
                        "baseUrl": "https://proxy.example",
                        "headers": {
                            "x-provider": "provider-header"
                        },
                        "modelOverrides": {
                            "claude-sonnet-4-5": {
                                "name": "Proxy Sonnet",
                                "contextWindow": 12345,
                                "maxTokens": 678,
                                "cost": {
                                    "input": 9
                                },
                                "headers": {
                                    "x-model": "model-header"
                                }
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val registry = ModelRegistry.create(auth, modelsPath.toString())
        val model = registry.find(ANTHROPIC_PROVIDER, "claude-sonnet-4-5")

        assertNotNull(model)
        model!!
        assertEquals("Proxy Sonnet", model.name)
        assertEquals("https://proxy.example", model.baseUrl)
        assertEquals(12345, model.contextWindow)
        assertEquals(678, model.maxTokens)
        assertEquals(9.0, model.cost.input)
        assertTrue(model.cost.output > 0.0)

        val authResult = registry.getApiKeyAndHeaders(model)
        assertTrue(authResult.ok, authResult.error)
        assertEquals("sk-auth", authResult.apiKey)
        assertEquals("provider-header", authResult.headers["x-provider"])
        assertEquals("model-header", authResult.headers["x-model"])
    }

    @Test
    fun `invalid models json records error and keeps built in models`() {
        val auth = AuthStorage.create(Files.createTempFile("auth", ".json").toString())
        val modelsPath = Files.createTempFile("models", ".json")
        Files.writeString(
            modelsPath,
            """
            {
                "providers": {
                    "custom-ai": {
                        "models": [
                            {
                                "id": "broken",
                                "api": "anthropic-messages"
                            }
                        ]
                    }
                }
            }
            """.trimIndent(),
        )

        val registry = ModelRegistry.create(auth, modelsPath.toString())

        assertTrue(registry.getError()?.contains("baseUrl") == true)
        assertNotNull(registry.find(ANTHROPIC_PROVIDER, "claude-sonnet-4-5"))
        assertFalse(registry.getAll().any { it.provider == "custom-ai" && it.id == "broken" })
    }
}
