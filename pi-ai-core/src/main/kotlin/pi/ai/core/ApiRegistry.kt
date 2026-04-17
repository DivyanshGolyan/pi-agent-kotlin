package pi.ai.core

public interface ApiProvider {
    public val api: Api

    public fun stream(
        model: Model<*>,
        context: Context,
        options: StreamOptions? = null,
    ): AssistantMessageEventStream

    public fun streamSimple(
        model: Model<*>,
        context: Context,
        options: SimpleStreamOptions? = null,
    ): AssistantMessageEventStream
}

private val apiProviders: MutableMap<Api, ApiProvider> = linkedMapOf()

public fun registerApiProvider(provider: ApiProvider) {
    apiProviders[provider.api] = provider
}

public fun clearApiProviders() {
    apiProviders.clear()
}

public fun getApiProvider(api: Api): ApiProvider? = apiProviders[api]
