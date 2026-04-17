package pi.ai.core

public fun getEnvApiKey(provider: Provider): String? =
    when (provider) {
        ANTHROPIC_PROVIDER -> System.getenv("ANTHROPIC_API_KEY")
        else -> null
    }
